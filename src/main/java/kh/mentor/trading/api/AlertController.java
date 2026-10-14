package kh.mentor.trading.api;

import kh.mentor.trading.alerts.AlertScanService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only usable from loopback by default. Caller should run inside the same host. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertScanService service;
    public AlertController(AlertScanService service) { this.service = service; }

    @PostMapping(path="/scan", produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> scan() {
        String message = service.scanForNewPaperAlerts();
        return message.isBlank() ? ResponseEntity.noContent().build() : ResponseEntity.ok(message);
    }
}
