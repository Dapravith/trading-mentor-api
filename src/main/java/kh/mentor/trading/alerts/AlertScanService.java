package kh.mentor.trading.alerts;

import java.util.List;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Produces durable events; sending Telegram is a separate operation. */
@Service
public class AlertScanService {
    private final ScanService scanService;
    private final AlertRepository alerts;
    private final String interval;
    private final double paperBalance;

    public AlertScanService(ScanService scanService, AlertRepository alerts,
        @Value("${trading.interval:4h}") String interval,
        @Value("${trading.paper-balance-usdt:1000}") double paperBalance) {
        this.scanService = scanService;
        this.alerts = alerts;
        this.interval = interval;
        this.paperBalance = paperBalance;
    }

    public int scanAndQueue() {
        // Fetch both before writing: an upstream failure must not publish a partial scan.
        List<MarketAnalysis> analyses = List.of(
            scanService.scan("BTCUSDT", interval, paperBalance),
            scanService.scan("ETHUSDT", interval, paperBalance));
        int queued = 0;
        for (MarketAnalysis analysis : analyses) {
            if (alerts.enqueue(analysis)) queued++;
        }
        return queued;
    }
}
