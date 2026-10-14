package kh.mentor.trading.backtest;

/** One completed hypothetical long-spot position. */
public record PaperTrade(
    long entryCandleOpenMs,
    long exitCandleCloseMs,
    double entry,
    double exit,
    double quantity,
    double stop,
    double target,
    double netPnlUsdt,
    ExitReason exitReason
) {
    public enum ExitReason { STOP, TARGET, END_OF_SAMPLE }
}
