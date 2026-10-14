package kh.mentor.trading.market;

import java.util.List;

public interface MarketDataProvider {
    List<Candle> fetchClosedCandles(String symbol, String interval, int limit);
}
