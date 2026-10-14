package kh.mentor.trading.market;

import java.util.List;

/** Complete UTC-day market snapshot interface; keeps research independent of Binance. */
public interface HistoricalMarketDataProvider {
    List<Candle> fetchHistoricalDaily(String symbol, long startMs, long endExclusiveMs);
}
