package kh.mentor.trading.backtest;

import java.util.Set;
import kh.mentor.trading.market.MarketDataProvider;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.StrategyEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Fetches a bounded verified candle series, delegates to the pure simulation engine. */
@Service
public class BacktestService {
    private static final Set<String> SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private static final Set<String> INTERVALS = Set.of("4h", "1d");
    private final MarketDataProvider provider;
    private final BacktestEngine engine;

    public BacktestService(MarketDataProvider provider, StrategyEngine strategy,
        RiskPlanner risk,
        @Value("${trading.fee-per-side:0.001}") double fee,
        @Value("${trading.slippage-per-side:0.001}") double slippage) {
        this.provider = provider;
        this.engine = new BacktestEngine(strategy, risk, fee, slippage);
    }

    public BacktestResult run(String symbol, String interval, double startingBalance) {
        if (!SYMBOLS.contains(symbol) || !INTERVALS.contains(interval)
            || !Double.isFinite(startingBalance) || startingBalance <= 0
            || startingBalance > 1_000_000_000) {
            throw new IllegalArgumentException("Invalid symbol, interval or starting balance");
        }
        // Existing provider validates Binance time, stale candles and sequence.
        return engine.run(symbol, interval,
            provider.fetchClosedCandles(symbol, interval, 500), startingBalance);
    }
}
