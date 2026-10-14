package kh.mentor.trading.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import kh.mentor.trading.alerts.AlertManualRetryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Sensitive operator recovery: disabled by default, loopback-only and requires
 * a separate strong secret. Never replay a SENT event or bulk-retry failures.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@ConditionalOnProperty(prefix="trading.alert",
    name={"delivery-enabled","manual-retry-enabled"},havingValue="true")
public class AlertManualRetryController {
    private final AlertManualRetryService service;
    private final byte[] key;

    public AlertManualRetryController(AlertManualRetryService service,
         @Value("${trading.alert.operator-key:}") String configuredKey) {
        if(configuredKey.length()<32 || configuredKey.startsWith("REPLACE")
            || configuredKey.startsWith("your-") || configuredKey.isBlank()) {
            throw new IllegalStateException(
                "ALERT_OPERATOR_KEY must be a strong independent secret when manual retry is enabled");
        }
        this.service=service;
        this.key=configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    private boolean authorized(String supplied) {
        return supplied!=null && MessageDigest.isEqual(
            key,supplied.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/failed")
    public ResponseEntity<?> failed(
        @RequestHeader(value="X-Alert-Operator-Key",required=false) String supplied,
        @RequestParam(defaultValue="20") int limit) {
        if (!authorized(supplied)) {
            return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        }
        return ResponseEntity.ok(service.failed(limit));
    }

    public record RetryRequest(String reason) {}

    @PostMapping("/failed/{eventId}/retry")
    public ResponseEntity<?> retry(
        @RequestHeader(value="X-Alert-Operator-Key",required=false) String supplied,
        @PathVariable String eventId,
        @RequestBody(required=false) RetryRequest request) {
        if (!authorized(supplied)) {
            return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        }
        var result=service.retry(eventId,request==null?null:request.reason());
        return switch(result.status()) {
            case "QUEUED" -> ResponseEntity.accepted().body(result);
            case "NOT_FOUND" -> ResponseEntity.status(404).body(
                Map.of("error","event_not_found"));
            case "NOT_RETRYABLE" -> ResponseEntity.status(409).body(
                Map.of("error","event_not_failed",
                    "status",result.previousStatus()));
            default -> throw new IllegalStateException("Unexpected retry outcome");
        };
    }
}
