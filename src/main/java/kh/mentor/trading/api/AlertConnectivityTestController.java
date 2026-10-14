package kh.mentor.trading.api;

import java.util.Map;
import kh.mentor.trading.alerts.AlertRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit local-only channel test.
 * It never emits a financial signal, and is absent unless BOTH flags are enabled.
 * Actual delivery is performed by the same ack/retry worker as paper alerts.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@ConditionalOnProperty(prefix = "trading.alert",
    name = {"delivery-enabled", "test-endpoint-enabled"}, havingValue = "true")
public class AlertConnectivityTestController {
    private final AlertRepository repository;
    public AlertConnectivityTestController(AlertRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/test")
    public Map<String, String> queueTest() {
        String id = repository.enqueueConnectivityTest();
        return Map.of("eventId", id, "status", "QUEUED", "mode", "TEST_ONLY",
            "message", "Check /api/v1/alerts/status for SENT; no real trade involved");
    }
}
