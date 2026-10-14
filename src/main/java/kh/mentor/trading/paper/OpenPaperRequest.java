package kh.mentor.trading.paper;

/** Prices are entered by the learner and NOT verified against live Binance quotes. */
public record OpenPaperRequest(String requestId, String symbol, double entryPrice,
    double stopPrice, double targetPrice, boolean confirmPaperOnly,
    String thesis, String emotion) {}
