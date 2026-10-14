package kh.mentor.trading.api;

import java.sql.SQLException;
import java.util.Map;
import kh.mentor.trading.alerts.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit local-only channel test. Enabled only when delivery and test flags are true.
 * The durable outbox is responsible for Telegram delivery and provider ACK.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@ConditionalOnProperty(prefix = "trading.alert",
    name = {"delivery-enabled", "test-endpoint-enabled"}, havingValue = "true")
public class AlertConnectivityTestController {
    private static final Logger log =
        LoggerFactory.getLogger(AlertConnectivityTestController.class);
    private final AlertRepository repository;

    public AlertConnectivityTestController(AlertRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> queueTest() {
        String id = repository.enqueueConnectivityTest();
        return ResponseEntity.accepted().body(Map.of("eventId", id, "status", "QUEUED",
            "mode", "TEST_ONLY",
            "message", "Use /api/v1/alerts/events/{eventId} to verify Telegram acknowledgement"));
    }

    /**
     * A valid test endpoint returning 500 is usually an outbox storage problem,
     * not incorrect Telegram delivery flags. Report a safe SQLSTATE for diagnosis.
     * Never print SQL parameter values, bot tokens, chat IDs or news credentials.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleOutboxFailure(DataAccessException ex) {
        SQLException sql = null;
        Throwable cursor = ex;
        for (int i = 0; cursor != null && i < 12; i++, cursor = cursor.getCause()) {
            if (cursor instanceof SQLException found) { sql = found; break; }
        }
        String state = sql != null && sql.getSQLState() != null
            ? sql.getSQLState() : "UNKNOWN";
        int vendorCode = sql == null ? 0 : sql.getErrorCode();
        log.error("telegram_test_outbox_enqueue_failed exception={} sqlState={} vendorCode={}",
            ex.getClass().getSimpleName(), state, vendorCode);
        return ResponseEntity.status(503).body(Map.of(
            "status", "FAILED", "errorCode", "OUTBOX_DATABASE_UNAVAILABLE",
            "message", "Could not persist Telegram test. Inspect server log: "
                + "telegram_test_outbox_enqueue_failed (SQLSTATE " + state + ")"));
    }
}
