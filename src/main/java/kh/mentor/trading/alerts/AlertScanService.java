package kh.mentor.trading.alerts;

import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public String scanForNewPaperAlerts() {
        // No LLM, no exchange orders, no Telegram credentials in this service.
        // If any upstream request fails, fail the whole scan (Hermes script returns nonzero).
        List<MarketAnalysis> analyses = List.of(
            scanService.scan("BTCUSDT", interval, paperBalance),
            scanService.scan("ETHUSDT", interval, paperBalance)
        );
        List<String> messages = new ArrayList<>();
        for (MarketAnalysis result : analyses) {
            if (alerts.recordNew(result)) messages.add(AlertFormatter.format(result));
        }
        return String.join("\n\n", messages);
    }
}
