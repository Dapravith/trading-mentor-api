package kh.mentor.trading.paper;

/** Persisted hypothetical trading position; zero real exchange connectivity. */
public record PaperPosition(String id, String requestId, String symbol,
    double entryPrice, double stopPrice, double targetPrice, double quantity,
    double entryCashUsdt, long openedAtMs, String status, Double closePrice,
    Double closeCashUsdt, Double netPnlUsdt, Long closedAtMs,
    String thesis, String emotion, String exitReason, String reflection) {}
