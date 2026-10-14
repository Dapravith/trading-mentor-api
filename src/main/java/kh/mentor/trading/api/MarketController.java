package kh.mentor.trading.api;

import java.util.Map;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MarketController {
    private final ScanService scanService;
    private final double defaultPaperBalance;

    public MarketController(ScanService scanService,
        @Value("${trading.paper-balance-usdt:1000}") double defaultPaperBalance) {
        this.scanService = scanService;
        this.defaultPaperBalance = defaultPaperBalance;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "executionEnabled", false, "mode", "READ_ONLY_PAPER_RESEARCH");
    }

    @GetMapping("/market/scan")
    public MarketAnalysis scan(@RequestParam(defaultValue="BTCUSDT") String symbol,
        @RequestParam(defaultValue="4h") String interval,
        @RequestParam(required=false) Double paperBalance) {
        return scanService.scan(symbol, interval, paperBalance == null ? defaultPaperBalance : paperBalance);
    }
}
