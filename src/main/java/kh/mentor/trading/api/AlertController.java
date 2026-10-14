package kh.mentor.trading.api;

import java.util.Map;
import kh.mentor.trading.alerts.AlertRepository;
import kh.mentor.trading.alerts.AlertScanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator API is loopback-only; no real orders, Telegram tokens, or message payloads exposed. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertScanService service;
    private final AlertRepository repository;
    public AlertController(AlertScanService service, AlertRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping("/scan")
    public Map<String, Object> scan() {
        int queued = service.scanAndQueue();
        return Map.of("queued", queued, "mode", "PAPER_ONLY",
            "message", "Events recorded for the delivery worker; none sent by this request");
    }

    @GetMapping("/events/{eventId}")
    public kh.mentor.trading.alerts.OutboxEventStatus eventStatus(
        @org.springframework.web.bind.annotation.PathVariable String eventId) {
        return repository.eventStatus(eventId);
    }

    /** Inspect earlier bot connectivity success without enqueuing another test. */
    @GetMapping("/test/latest")
    public org.springframework.http.ResponseEntity<kh.mentor.trading.alerts.OutboxEventStatus>
        latestTest() {
        var result = repository.latestConnectivityTest();
        return result == null
            ? org.springframework.http.ResponseEntity.noContent().build()
            : org.springframework.http.ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public Map<String, Integer> status() { return repository.statusCounts(); }
}
