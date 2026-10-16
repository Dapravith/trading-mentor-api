package kh.mentor.trading.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Privacy-limited daily Binance account check. A fixed-delay poll catches
 * app restarts after 09:00 Cambodia time; the database date is the idempotency key.
 * No account amounts, keys or exchange orders are exposed to Telegram.
 *
 * Sending is at-least-once: a crash after Telegram ACK and before the H2
 * update can very rarely cause a duplicate after the lease expires.
 */
@Component
@ConditionalOnProperty(prefix="trading.account",
    name={"enabled","daily-enabled"},havingValue="true")
public class BinanceDailyAccountMonitor {
    private static final Logger log=LoggerFactory.getLogger(BinanceDailyAccountMonitor.class);
    private static final ZoneId ICT=ZoneId.of("Asia/Phnom_Penh");
    private static final LocalTime DUE_AT=LocalTime.of(9,0);
    private static final int MAX_ATTEMPTS=3;
    private static final long LEASE_MS=120_000L;
    private final BinanceSpotAccountReader reader;
    private final JdbcTemplate jdbc;
    private final String botToken;
    private final String privateChatId;
    private final JsonMapper mapper;
    private final Clock clock;
    @FunctionalInterface interface PrivateSender { long send(String message); }
    private final PrivateSender sender;
    private final HttpClient http=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public BinanceDailyAccountMonitor(BinanceSpotAccountReader reader,JdbcTemplate jdbc,
        JsonMapper mapper,
        @Value("${trading.account.daily-bot-token:}") String token,
        @Value("${trading.account.daily-private-chat-id:}") String chat) {
        if(token.isBlank() || !chat.matches("[1-9][0-9]{3,19}")) {
            throw new IllegalStateException(
                "Daily Binance digest requires bot credentials and a private user chat ID");
        }
        this.reader=reader;
        this.jdbc=jdbc;
        this.mapper=mapper;
        this.botToken=token;
        this.privateChatId=chat;
        this.clock=Clock.systemUTC();
        this.sender=this::sendToPrivateChat;
    }

    /** Package-private deterministic seam: no actual Telegram request in tests. */
    BinanceDailyAccountMonitor(BinanceSpotAccountReader reader,JdbcTemplate jdbc,
        PrivateSender sender,Clock clock) {
        this.reader=reader;
        this.jdbc=jdbc;
        this.sender=sender;
        this.clock=clock;
        this.botToken="";
        this.privateChatId="";
        this.mapper=new JsonMapper();
    }

    /** Backward-compatible test seam. */
    BinanceDailyAccountMonitor(BinanceSpotAccountReader reader,JdbcTemplate jdbc,
        PrivateSender sender) {
        this(reader,jdbc,sender,Clock.systemUTC());
    }

    @Scheduled(fixedDelayString="${trading.account.daily-poll-ms:900000}",
        initialDelayString="${trading.account.daily-initial-delay-ms:30000}")
    public void checkDaily() {
        ZonedDateTime cambodiaTime=ZonedDateTime.now(clock).withZoneSameInstant(ICT);
        if(cambodiaTime.toLocalTime().isBefore(DUE_AT)) return;
        String date=cambodiaTime.toLocalDate().toString();
        long now=clock.millis();
        try {
            int attempt=claim(date,now);
            if(attempt==0) return;
            try {
                var result=reader.check(); // Signed GET + strict API key permission checks
                String msg="🔐 DAILY BINANCE SPOT ACCOUNT CHECK\n"
                    +"Date: "+date+" (Cambodia)\n"
                    +"Read-only API: VERIFIED\n"
                    +"Non-zero asset types: "+result.nonZeroAssetCount()+"\n"
                    +"BTC held: "+yesNo(result.holdsBtc())
                    +" | ETH held: "+yesNo(result.holdsEth())+"\n"
                    +"⚠️ Private read-only health check, not a BUY/SELL recommendation. "
                    +"No real order, transfer or withdrawal is enabled.";
                long providerMessageId=sender.send(msg);
                if(providerMessageId<=0) {
                    throw new IllegalStateException("Private Telegram ACK is missing");
                }
                int updated=jdbc.update("""
                    UPDATE binance_account_digest
                    SET status='SENT', provider_message_id=?, sent_at_ms=?,
                        lease_until_ms=NULL, error_code=NULL
                    WHERE report_date=? AND status='SENDING' AND attempts=?
                    """,providerMessageId,clock.millis(),date,attempt);
                if(updated!=1) {
                    log.error("binance_digest_ack_write_conflict date={}",date);
                } else {
                    log.info("binance_daily_digest_sent date={} attempt={}",date,attempt);
                }
            } catch(RuntimeException failure) {
                // Do not log exception messages or HTTP requests: sensitive metadata.
                String category="DAILY_ACCOUNT_OR_TELEGRAM_ERROR";
                try {
                    String status=attempt>=MAX_ATTEMPTS?"FAILED":"PENDING";
                    long next=clock.millis()+Math.min(900_000L,60_000L*(1L<<(attempt-1)));
                    jdbc.update("""
                        UPDATE binance_account_digest
                        SET status=?, next_attempt_ms=?, lease_until_ms=NULL,
                            error_code=?
                        WHERE report_date=? AND status='SENDING' AND attempts=?
                        """,status,next,category,date,attempt);
                } catch(RuntimeException storageFailure) {
                    // Lease may recover after a crashed process. Never delete H2 rows.
                    log.error("binance_daily_digest_failure_record_unavailable date={}",date);
                }
                log.warn("binance_daily_digest_failed date={} attempt={} category={}",
                    date,attempt,failure.getClass().getSimpleName());
            }
        } catch(RuntimeException storageFailure) {
            log.error("binance_daily_digest_db_unavailable date={} type={}",
                date,storageFailure.getClass().getSimpleName());
        }
    }

    /**
     * Atomic date lease: no concurrent worker may send the same day's report.
     * A FAILED row is terminal for operator review; no infinite retries.
     */
    private int claim(String date,long now) {
        try {
            jdbc.update("""
                INSERT INTO binance_account_digest
                    (report_date,status,attempts,next_attempt_ms,created_at_ms)
                VALUES (?,'PENDING',0,?,?)
                """,date,now,now);
        } catch(DuplicateKeyException exists) {
            // Daily row already exists; safe to inspect its claim status below.
        }
        jdbc.update("""
            UPDATE binance_account_digest
            SET status='FAILED', lease_until_ms=NULL, error_code='LEASE_EXPIRED'
            WHERE report_date=? AND status='SENDING' AND lease_until_ms<=?
              AND attempts>=?
            """,date,now,MAX_ATTEMPTS);
        int claimed=jdbc.update("""
            UPDATE binance_account_digest
            SET status='SENDING', attempts=attempts+1,
                lease_until_ms=?, error_code=NULL
            WHERE report_date=? AND attempts<?
              AND ((status='PENDING' AND next_attempt_ms<=?)
                OR (status='SENDING' AND lease_until_ms<=?))
            """,now+LEASE_MS,date,MAX_ATTEMPTS,now,now);
        if(claimed==0) return 0;
        Integer attempts=jdbc.queryForObject("""
            SELECT attempts FROM binance_account_digest
            WHERE report_date=? AND status='SENDING'
            """,Integer.class,date);
        return attempts==null?0:attempts;
    }

    private long sendToPrivateChat(String text) {
        try {
            String body=mapper.writeValueAsString(Map.of(
                "chat_id",privateChatId,"text",text,"disable_web_page_preview",true));
            var request=HttpRequest.newBuilder(URI.create(
                "https://api.telegram.org/bot"+botToken+"/sendMessage"))
                .header("Content-Type","application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)
                throw new IllegalStateException("Telegram private digest rejected");
            var json=mapper.readTree(response.body());
            long id=json.path("result").path("message_id").asLong(-1);
            if(!json.path("ok").asBoolean(false)||id<=0)
                throw new IllegalStateException("Private Telegram ACK missing");
            return id;
        } catch(java.io.IOException ex) {
            throw new IllegalStateException("Private Telegram connectivity failure");
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Private Telegram request interrupted");
        }
    }

    private static String yesNo(boolean value) {return value?"YES":"NO";}
}
