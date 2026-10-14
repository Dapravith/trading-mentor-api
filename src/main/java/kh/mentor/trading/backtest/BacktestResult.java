package kh.mentor.trading.backtest;

import java.util.List;

/** Bounded educational replay; returns neither a forecast nor a live order. */
public record BacktestResult(
    String symbol,
    String interval,
    int completedCandles,
    int warmupCandles,
    double startingBalanceUsdt,
    double endingBalanceUsdt,
    double netReturnPct,
    double buyAndHoldReturnPct,
    double maxClosedTradeDrawdownPct,
    int completedTrades,
    int winningTrades,
    double winRatePct,
    List<PaperTrade> trades,
    List<String> limitations
) {}
