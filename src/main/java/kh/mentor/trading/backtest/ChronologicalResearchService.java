package kh.mentor.trading.backtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import kh.mentor.trading.market.Candle;
import kh.mentor.trading.market.HistoricalMarketDataProvider;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.StrategyEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Chronological fixed-strategy holdout evaluation, using the same simulation
 * and risk rules as the recent research endpoint. This does not refit weights
 * or optimize strategy parameters. It is NOT full walk-forward optimization.
 */
@Service
public class ChronologicalResearchService {
    private static final Set<String> SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private final HistoricalMarketDataProvider history;
    private final BacktestEngine engine;

    public ChronologicalResearchService(HistoricalMarketDataProvider history,
        StrategyEngine strategy, RiskPlanner risk,
        @Value("${trading.fee-per-side:0.001}") double fee,
        @Value("${trading.slippage-per-side:0.001}") double slippage) {
        this.history = history;
        this.engine = new BacktestEngine(strategy, risk, fee, slippage);
    }

    public ChronologicalResearchResult run(String symbol, String from, String to,
                                            double startingBalance) {
        if (!SYMBOLS.contains(symbol) || !Double.isFinite(startingBalance)
            || startingBalance <= 0 || startingBalance > 1_000_000_000) {
            throw new IllegalArgumentException("Invalid research pair or starting balance");
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Dates must be ISO yyyy-MM-dd", e);
        }
        long startMs = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long endMs = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long days = end.toEpochDay() - start.toEpochDay();
        if (startMs <= 0 || days < 360 || days > 2000) {
            throw new IllegalArgumentException("Use between 360 and 2000 completed UTC days");
        }
        List<Candle> candles = history.fetchHistoricalDaily(symbol, startMs, endMs);
        if (candles.size() != days) {
            throw new IllegalArgumentException("Historical data incomplete: no report produced");
        }
        int trainEnd = candles.size() / 2;
        int validationEnd = (candles.size() * 3) / 4;
        // Validation and testing see the previous 60 completed candles as warmup,
        // but their trade entries begin only within their own date window.
        BacktestResult train = engine.run(symbol, "1d",
            candles.subList(0, trainEnd), startingBalance);
        BacktestResult validation = engine.run(symbol, "1d",
            candles.subList(trainEnd - BacktestEngine.WARMUP, validationEnd), startingBalance);
        BacktestResult test = engine.run(symbol, "1d",
            candles.subList(validationEnd - BacktestEngine.WARMUP, candles.size()), startingBalance);
        return new ChronologicalResearchResult(symbol, "1d", from, to,
            digest(candles), "breakout-v1", candles.size(), train, validation, test,
            List.of("Fixed parameters; no training, retuning or nested walk-forward optimization",
                "Chronological 50% train / 25% validation / 25% test segments",
                "Each segment resets starting equity and contains 60 prior warm-up bars",
                "OHLC assumptions, no order book or liquidity modeling; not evidence of future profitability",
                "Keep final test window untouched when changing strategies"));
    }

    private static String digest(List<Candle> candles) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Candle c : candles) {
                String row = c.openTimeMs() + "," + c.closeTimeMs() + ","
                    + Double.toHexString(c.open()) + "," + Double.toHexString(c.high()) + ","
                    + Double.toHexString(c.low()) + "," + Double.toHexString(c.close()) + ","
                    + Double.toHexString(c.volume()) + "\n";
                md.update(row.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
