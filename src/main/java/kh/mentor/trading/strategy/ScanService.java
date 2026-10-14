package kh.mentor.trading.strategy;

import java.util.Set;
import kh.mentor.trading.market.MarketDataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScanService {
    private static final Set<String> SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private static final Set<String> INTERVALS = Set.of("4h", "1d");
    private final MarketDataProvider provider;
    private final StrategyEngine strategy;
    private final int candleLimit;

    public ScanService(MarketDataProvider provider, StrategyEngine strategy,
        @Value("${trading.candles-limit:200}") int candleLimit) {
        this.provider = provider;
        this.strategy = strategy;
        this.candleLimit = candleLimit;
    }

    public MarketAnalysis scan(String symbol, String interval, double paperBalanceUsdt) {
        if (!SYMBOLS.contains(symbol) || !INTERVALS.contains(interval) ||
            !Double.isFinite(paperBalanceUsdt) || paperBalanceUsdt <= 0 ||
            paperBalanceUsdt > 1_000_000_000) {
            throw new IllegalArgumentException("Unsupported symbols, interval, or paper balance");
        }
        return strategy.analyze(symbol, interval,
            provider.fetchClosedCandles(symbol, interval, candleLimit), paperBalanceUsdt);
    }
}
