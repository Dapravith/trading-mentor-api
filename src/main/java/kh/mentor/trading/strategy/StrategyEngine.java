package kh.mentor.trading.strategy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.market.Candle;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import org.springframework.stereotype.Service;

/** Unvalidated learning hypothesis: EMA trend + 20-candle breakout + volume confirmation. */
@Service
public class StrategyEngine {
    private final RiskPlanner riskPlanner;
    public StrategyEngine(RiskPlanner riskPlanner) { this.riskPlanner = riskPlanner; }

    public MarketAnalysis analyze(String symbol, String interval, List<Candle> candles, double paperBalance) {
        if (candles == null || candles.size() < 60) throw new IllegalArgumentException("Need >=60 completed candles");
        List<Double> closes = candles.stream().map(Candle::close).toList();
        Candle last = candles.getLast();
        double current = last.close();
        double ema20 = Indicators.ema(closes, 20);
        double ema50 = Indicators.ema(closes, 50);
        double rsi14 = Indicators.rsi(closes, 14);
        double atr14 = Indicators.atr(candles, 14);
        double prevVolumeAvg = candles.subList(candles.size()-21,candles.size()-1)
            .stream().mapToDouble(Candle::volume).average().orElse(0);
        double volumeRatio = prevVolumeAvg > 0 ? last.volume() / prevVolumeAvg : 0;
        double previousHigh = candles.subList(candles.size()-21,candles.size()-1)
            .stream().mapToDouble(Candle::high).max().orElse(Double.MAX_VALUE);

        List<String> failures = new ArrayList<>();
        if (!(current > ema20 && ema20 > ema50)) failures.add("Uptrend criterion not met");
        if (!(rsi14 >= 50 && rsi14 <= 70)) failures.add("Momentum outside 50-70 RSI range");
        if (!(current > previousHigh)) failures.add("No close above previous 20-candle high");
        if (!(volumeRatio >= 1.2)) failures.add("Volume below 1.2x previous 20-candle average");
        if (!(atr14 > 0)) failures.add("ATR not positive");
        RiskPlan paperRisk = null;
        String status = "NO_TRADE";
        if (failures.isEmpty()) {
            double stop = current - 2 * atr14;
            double target = current + 6 * atr14;
            if (stop > 0) {
                paperRisk = riskPlanner.calculate(paperBalance, current, stop, target);
                if (paperRisk.valid()) status = "PAPER_SETUP_WATCH";
                else failures.addAll(paperRisk.reasons());
            } else failures.add("Hypothetical stop would be non-positive");
        }
        if (failures.isEmpty()) failures.add("Study conditions passed; NOT a verified profitable signal");
        return new MarketAnalysis(symbol, interval, last.closeTimeMs(),
            Instant.ofEpochMilli(last.closeTimeMs()).toString(), round(current, 8),
            round(ema20, 8), round(ema50, 8), round(rsi14, 2), round(atr14, 8),
            round(volumeRatio, 3), status, List.copyOf(failures), paperRisk,
            "Binance public Spot /api/v3/klines, last COMPLETED candle",
            "Educational paper-trading research. Not a live quote or real trading instruction.");
    }

    private static double round(double value, int precision) {
        double factor = Math.pow(10, precision);
        return Math.round(value * factor) / factor;
    }
}
