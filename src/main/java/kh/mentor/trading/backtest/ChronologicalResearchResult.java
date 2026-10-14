package kh.mentor.trading.backtest;

import java.util.List;

/** Fixed-parameter chronological holdout, NOT optimized walk-forward training. */
public record ChronologicalResearchResult(
    String symbol,
    String interval,
    String startInclusiveUtc,
    String endExclusiveUtc,
    String datasetSha256,
    String strategyVersion,
    int datasetCandles,
    BacktestResult training,
    BacktestResult validation,
    BacktestResult testing,
    List<String> warnings
) {}
