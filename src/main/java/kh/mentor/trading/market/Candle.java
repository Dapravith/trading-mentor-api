package kh.mentor.trading.market;

/** A validated Binance OHLCV candle. All times are exchange timestamps in milliseconds. */
public record Candle(long openTimeMs, long closeTimeMs, double open, double high,
                     double low, double close, double volume) {
    public Candle {
        if (openTimeMs < 0 || closeTimeMs <= openTimeMs ||
            !Double.isFinite(open) || !Double.isFinite(high) ||
            !Double.isFinite(low) || !Double.isFinite(close) || !Double.isFinite(volume) ||
            open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0 ||
            high < Math.max(Math.max(open, close), low) || low > Math.min(open, close)) {
            throw new IllegalArgumentException("Invalid candle OHLCV or timestamps");
        }
    }
}
