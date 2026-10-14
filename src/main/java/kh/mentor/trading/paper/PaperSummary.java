package kh.mentor.trading.paper;

public record PaperSummary(double startingBalanceUsdt, double availableCashUsdt,
    double cashReservedInOpenPositionsUsdt, int openPositions, int closedPositions,
    double realizedPnlUsdt, String limitation) {}
