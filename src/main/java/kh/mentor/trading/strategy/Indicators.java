package kh.mentor.trading.strategy;

import kh.mentor.trading.market.Candle;
import java.util.List;

/** Indicator calculations only use completed candles passed to the engine. */
public final class Indicators {
    private Indicators() {}

    public static double ema(List<Double> values, int length) {
        if (length < 1 || values.size() < length) throw new IllegalArgumentException("Insufficient EMA history");
        double result = 0;
        for (int i = 0; i < length; i++) result += values.get(i);
        result /= length;
        double alpha = 2.0 / (length + 1);
        for (int i = length; i < values.size(); i++) {
            result = values.get(i) * alpha + result * (1 - alpha);
        }
        return result;
    }

    public static double rsi(List<Double> closes, int length) {
        if (length < 1 || closes.size() <= length) throw new IllegalArgumentException("Insufficient RSI history");
        double gain = 0, loss = 0;
        for (int i = 1; i <= length; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= length; loss /= length;
        for (int i = length + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            gain = (gain * (length - 1) + Math.max(d, 0)) / length;
            loss = (loss * (length - 1) + Math.max(-d, 0)) / length;
        }
        if (gain == 0 && loss == 0) return 50;
        if (loss == 0) return 100;
        return 100 - 100 / (1 + gain / loss);
    }

    public static double atr(List<Candle> candles, int length) {
        if (length < 1 || candles.size() <= length) throw new IllegalArgumentException("Insufficient ATR history");
        double sum = 0;
        for (int i = 1; i <= length; i++) sum += trueRange(candles.get(i-1), candles.get(i));
        double result = sum / length;
        for (int i = length + 1; i < candles.size(); i++) {
            result = (result * (length - 1) + trueRange(candles.get(i-1), candles.get(i))) / length;
        }
        return result;
    }

    private static double trueRange(Candle prev, Candle curr) {
        return Math.max(curr.high() - curr.low(),
            Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
    }
}
