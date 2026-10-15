package kh.mentor.trading.market;

public interface CurrentPriceProvider {
    double fetchCurrentPrice(String symbol);
}