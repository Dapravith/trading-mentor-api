package kh.mentor.trading.alerts;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-event, operator-initiated replay only. FAILED is terminal until an explicit
 * authorized action. Row lock + audit insert + requeue are one transaction.
 */
@Service
public class AlertManualRetryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AlertManualRetryService(JdbcTemplate jdbc,
                                   PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.tx=new TransactionTemplate(manager);
    }

    public List<FailedAlert> failed(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be 1..100");
        }
        return jdbc.query("""
            SELECT id, strategy_version, attempts, error_code, created_at_ms
            FROM alert_outbox WHERE status='FAILED'
            ORDER BY created_at_ms DESC, id DESC LIMIT ?
            """, (rs,row)->new FailedAlert(rs.getString("id"),
                rs.getString("strategy_version"),rs.getInt("attempts"),
                rs.getString("error_code"),rs.getLong("created_at_ms")),limit);
    }

    public RetryResult retry(String id,String reason) {
        if (id==null || !id.matches("[A-Za-z0-9:._-]{1,160}")) {
            throw new IllegalArgumentException("Invalid event identifier");
        }
        if (reason==null || reason.length()<10 || reason.length()>200
            || reason.chars().anyMatch(ch->ch<32 || ch==127)) {
            throw new IllegalArgumentException(
                "Document the fix/reason with 10 to 200 printable characters");
        }
        return tx.execute(transaction -> {
            var rows=jdbc.query("""
                SELECT status, attempts, error_code FROM alert_outbox
                WHERE id=? FOR UPDATE
                """, (rs,row)->new PriorState(rs.getString("status"),
                    rs.getInt("attempts"),rs.getString("error_code")),id);
            if (rows.isEmpty()) return new RetryResult(id,"NOT_FOUND",null);
            var before=rows.getFirst();
            if (!"FAILED".equals(before.status())) {
                return new RetryResult(id,"NOT_RETRYABLE",before.status());
            }
            long now=System.currentTimeMillis();
            int changed=jdbc.update("""
                UPDATE alert_outbox
                SET status='PENDING', attempts=0, next_attempt_ms=?,
                    lease_until_ms=NULL, error_code='MANUAL_RETRY_REQUESTED',
                    delivered_at_ms=NULL, provider_message_id=NULL
                WHERE id=? AND status='FAILED'
                """,now,id);
            if (changed!=1) throw new IllegalStateException("Retry conflict");
            // Audit is atomic with reset: if this insert fails, the event remains FAILED.
            jdbc.update("""
                INSERT INTO alert_retry_audit
                    (id, alert_id, prior_attempts, prior_error_code,
                     operator_reason, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """,UUID.randomUUID().toString(),id,before.attempts(),
                before.errorCode(),reason,now);
            return new RetryResult(id,"QUEUED","FAILED");
        });
    }

    private record PriorState(String status,int attempts,String errorCode) {}
    public record RetryResult(String eventId,String status,String previousStatus) {}
}
