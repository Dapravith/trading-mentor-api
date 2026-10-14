package kh.mentor.trading.alerts;

import java.util.List;
import java.util.Map;
import kh.mentor.trading.strategy.MarketAnalysis;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable at-least-once delivery outbox. No network I/O while a transaction is held. */
@Repository
public class AlertRepository {
    public static final String STRATEGY_VERSION = "breakout-v1";
    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Explicit connectivity test only. Never creates a market recommendation. */
    public String enqueueConnectivityTest() {
        long now = System.currentTimeMillis();
        String id = "connectivity-v1:" + java.util.UUID.randomUUID();
        String payload = "🧪 Connection test — Telegram is connected.\n"
            + "🕒 " + AlertFormatter.time(now) + "\n"
            + "⚠️ Test only — not a trading signal.";
        int inserted = jdbc.update("""
            INSERT INTO alert_outbox
              (id, symbol, candle_interval, candle_close_ms, strategy_version,
               payload, status, attempts, next_attempt_ms, created_at_ms)
            VALUES (?, 'SYSTEM', 'test', ?, 'connectivity-v1', ?,
                    'PENDING', 0, ?, ?)
            """, id, now, payload, now, now);
        if (inserted != 1) throw new IllegalStateException("Connectivity test was not queued");
        return id;
    }

    public boolean enqueue(MarketAnalysis analysis) {
        if (!"PAPER_SETUP_WATCH".equals(analysis.status())) return false;
        long now = System.currentTimeMillis();
        String id = STRATEGY_VERSION + ":" + analysis.symbol() + ":" +
            analysis.interval() + ":" + analysis.candleCloseMs();
        try {
            return jdbc.update("""
                INSERT INTO alert_outbox
                  (id, symbol, candle_interval, candle_close_ms, strategy_version,
                   payload, status, attempts, next_attempt_ms, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """, id, analysis.symbol(), analysis.interval(), analysis.candleCloseMs(),
                STRATEGY_VERSION, AlertFormatter.format(analysis), now, now) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false; // repeated scans of the same completed candle are idempotent
        }
    }

    /** External news events use the same deduplication and acknowledgement path. */
    public boolean enqueueNews(String id, long eventAtMs, String message) {
        if (!id.matches("hermes-news-v1:[a-z0-9][a-z0-9-]{8,119}")
            || message.isBlank() || message.length() > 3500) {
            throw new IllegalArgumentException("Invalid news event payload");
        }
        long now = System.currentTimeMillis();
        try {
            return jdbc.update("""
                INSERT INTO alert_outbox
                  (id, symbol, candle_interval, candle_close_ms, strategy_version,
                   payload, status, attempts, next_attempt_ms, created_at_ms)
                VALUES (?, 'NEWS', 'event', ?, 'hermes-news-v1', ?, 'PENDING', 0, ?, ?)
                """, id, eventAtMs, message, now, now) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** Find the most recently recorded Telegram connectivity test without queuing another one. */
    public OutboxEventStatus latestConnectivityTest() {
        var rows = jdbc.query("""
            SELECT id, status, attempts, delivered_at_ms, provider_message_id, error_code
            FROM alert_outbox WHERE strategy_version='connectivity-v1'
            ORDER BY created_at_ms DESC, id DESC LIMIT 1
            """, (rs, i) -> new OutboxEventStatus(
                rs.getString("id"), rs.getString("status"), rs.getInt("attempts"),
                rs.getObject("delivered_at_ms", Long.class),
                rs.getObject("provider_message_id", Long.class),
                rs.getString("error_code")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public OutboxEventStatus eventStatus(String id) {
        if (id == null || id.isBlank() || id.length() > 160) {
            throw new IllegalArgumentException("Invalid event identifier");
        }
        var items = jdbc.query("""
            SELECT id, status, attempts, delivered_at_ms, provider_message_id, error_code
            FROM alert_outbox WHERE id=?
            """, (rs,n) -> new OutboxEventStatus(
                rs.getString("id"), rs.getString("status"), rs.getInt("attempts"),
                rs.getObject("delivered_at_ms", Long.class),
                rs.getObject("provider_message_id", Long.class),
                rs.getString("error_code")), id);
        if (items.isEmpty()) throw new IllegalArgumentException("Unknown event identifier");
        return items.getFirst();
    }

    /** Recover a crashed sender after lease expiry; cap re-delivery attempts. */
    public void recoverExpired(long now, int maxAttempts) {
        jdbc.update("""
            UPDATE alert_outbox SET status='FAILED', lease_until_ms=NULL,
              error_code='LEASE_EXPIRED'
            WHERE status='SENDING' AND lease_until_ms<=? AND attempts>=?
            """, now, maxAttempts);
        jdbc.update("""
            UPDATE alert_outbox SET status='PENDING', lease_until_ms=NULL,
              next_attempt_ms=?, error_code='LEASE_EXPIRED'
            WHERE status='SENDING' AND lease_until_ms<=? AND attempts<?
            """, now, now, maxAttempts);
    }

    /** Concurrent dispatchers may race on SELECT, but only one can win the UPDATE. */
    public ClaimedAlert claim(long now, long leaseMillis, int maxAttempts) {
        List<String> candidateIds = jdbc.queryForList("""
            SELECT id FROM alert_outbox
            WHERE status='PENDING' AND next_attempt_ms<=? AND attempts<?
            ORDER BY created_at_ms, id LIMIT 20
            """, String.class, now, maxAttempts);
        for (String id : candidateIds) {
            int claimed = jdbc.update("""
                UPDATE alert_outbox SET status='SENDING', attempts=attempts+1,
                  lease_until_ms=?, error_code=NULL
                WHERE id=? AND status='PENDING' AND next_attempt_ms<=? AND attempts<?
                """, now + leaseMillis, id, now, maxAttempts);
            if (claimed == 1) {
                return jdbc.queryForObject("""
                    SELECT id, payload, attempts FROM alert_outbox WHERE id=?
                    """, (rs, row) -> new ClaimedAlert(rs.getString("id"),
                        rs.getString("payload"), rs.getInt("attempts")), id);
            }
        }
        return null;
    }

    public boolean markSent(ClaimedAlert claim, long sentAt, long providerMessageId) {
        return jdbc.update("""
            UPDATE alert_outbox SET status='SENT', delivered_at_ms=?,
              provider_message_id=?, lease_until_ms=NULL, error_code=NULL
            WHERE id=? AND status='SENDING' AND attempts=?
            """, sentAt, providerMessageId, claim.id(), claim.attempts()) == 1;
    }

    public boolean markFailed(ClaimedAlert claim, long now, int maxAttempts,
                              boolean permanent, String errorCode) {
        int delayPower = Math.min(10, Math.max(0, claim.attempts() - 1));
        long delayMillis = Math.min(1_800_000L, 30_000L * (1L << delayPower));
        boolean dead = permanent || claim.attempts() >= maxAttempts;
        return jdbc.update("""
            UPDATE alert_outbox SET status=?, next_attempt_ms=?,
              lease_until_ms=NULL, error_code=?
            WHERE id=? AND status='SENDING' AND attempts=?
            """, dead ? "FAILED" : "PENDING", now + delayMillis,
            errorCode, claim.id(), claim.attempts()) == 1;
    }

    public Map<String, Integer> statusCounts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String status : List.of("PENDING", "SENDING", "SENT", "FAILED")) counts.put(status, 0);
        jdbc.query("SELECT status, COUNT(*) AS total FROM alert_outbox GROUP BY status",
            rs -> { counts.put(rs.getString("status"), rs.getInt("total")); });
        return Map.copyOf(counts);
    }
}
