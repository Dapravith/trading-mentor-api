package kh.mentor.trading.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Java owns market scanning; Hermes does not need to schedule a second scan.
 * Disabled by default. Idempotency is enforced in the durable outbox.
 */
@Component
@ConditionalOnProperty(prefix="trading.scan", name="scheduling-enabled", havingValue="true")
public class MarketScanScheduler {
    private static final Logger log = LoggerFactory.getLogger(MarketScanScheduler.class);
    private final AlertScanService service;
    public MarketScanScheduler(AlertScanService service) { this.service = service; }

    @Scheduled(
        fixedDelayString="${trading.scan.interval-ms:900000}",
        initialDelayString="${trading.scan.initial-delay-ms:30000}")
    public void scan() {
        try {
            int count = service.scanAndQueue();
            if (count > 0) log.info("market_scan_enqueued count={}",count);
        } catch (RuntimeException exception) {
            // Fail closed: a Binance outage or invalid candle cannot become a signal.
            // Avoid dumping upstream response bodies or credentials in logs.
            log.warn("market_scan_failed type={}", exception.getClass().getSimpleName());
        }
    }
}
