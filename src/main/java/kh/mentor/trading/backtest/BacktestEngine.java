package kh.mentor.trading.backtest;

import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.market.Candle;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.StrategyEngine;

/**
 * Pure deterministic long-only replay, with one position at a time.
 * A signal observes only completed bars [0,i); entry is at bar i OPEN.
 * Same-bar stop/target collision is conservatively resolved as STOP.
 */
public final class BacktestEngine {
    public static final int WARMUP = 60;
    private final StrategyEngine strategy;
    private final RiskPlanner risk;
    private final double costPerSide;

    public BacktestEngine(StrategyEngine strategy, RiskPlanner risk,
                          double feePerSide, double slippagePerSide) {
        if (!Double.isFinite(feePerSide) || !Double.isFinite(slippagePerSide)
            || feePerSide < 0 || slippagePerSide < 0
            || feePerSide > .01 || slippagePerSide > .02) {
            throw new IllegalArgumentException("Invalid backtest costs");
        }
        this.strategy = strategy;
        this.risk = risk;
        this.costPerSide = feePerSide + slippagePerSide;
    }

    public BacktestResult run(String symbol, String interval, List<Candle> candles, double initialBalance) {
        if (candles == null || candles.size() <= WARMUP
            || !Double.isFinite(initialBalance) || initialBalance <= 0
            || initialBalance > 1_000_000_000) {
            throw new IllegalArgumentException("Need >60 completed candles and valid starting balance");
        }
        for (int i = 1; i < candles.size(); i++) {
            if (candles.get(i).openTimeMs() <= candles.get(i-1).closeTimeMs()) {
                throw new IllegalArgumentException("Candles overlap or are not ordered");
            }
        }
        double equity = initialBalance;
        double peak = initialBalance;
        double drawdownPct = 0;
        int winners = 0;
        Position position = null;
        List<PaperTrade> trades = new ArrayList<>();

        for (int i = WARMUP; i < candles.size(); i++) {
            Candle current = candles.get(i);
            if (position == null) {
                // Never provide the current candle's future high, low, close or volume.
                MarketAnalysis signal = strategy.analyze(symbol, interval,
                    candles.subList(0, i), equity);
                if ("PAPER_SETUP_WATCH".equals(signal.status())
                    && signal.paperRisk() != null && signal.paperRisk().valid()) {
                    RiskPlan proposal = signal.paperRisk();
                    // Recompute at actual next-bar open; skip adverse/favorable gaps
                    // outside the original stop/target plan rather than chasing price.
                    if (current.open() > proposal.stop() && current.open() < proposal.target()) {
                        RiskPlan sized = risk.calculate(equity, current.open(),
                            proposal.stop(), proposal.target());
                        if (sized.valid() && sized.quantity() > 0) {
                            position = new Position(current.openTimeMs(), current.open(),
                                sized.quantity(), sized.stop(), sized.target());
                        }
                    }
                }
            }
            if (position == null) continue;

            PaperTrade.ExitReason reason = null;
            double exit = 0;
            if (current.open() <= position.stop()) {
                reason = PaperTrade.ExitReason.STOP;
                exit = current.open(); // gap-through stop: fill at worse open
            } else if (current.low() <= position.stop()) {
                reason = PaperTrade.ExitReason.STOP;
                exit = position.stop(); // pessimistic if both stop and target hit
            } else if (current.high() >= position.target()) {
                reason = PaperTrade.ExitReason.TARGET;
                exit = position.target();
            } else if (i == candles.size() - 1) {
                reason = PaperTrade.ExitReason.END_OF_SAMPLE;
                exit = current.close();
            }
            if (reason != null) {
                double pnl = position.quantity() * (exit - position.entry())
                    - position.quantity() * (exit + position.entry()) * costPerSide;
                equity += pnl;
                trades.add(new PaperTrade(position.openMs(), current.closeTimeMs(),
                    position.entry(), exit, position.quantity(),
                    position.stop(), position.target(), pnl, reason));
                if (pnl > 0) winners++;
                peak = Math.max(peak, equity);
                drawdownPct = Math.max(drawdownPct, (peak - equity) / peak * 100.0);
                position = null;
            }
        }

        double benchmarkOpen = candles.get(WARMUP).open();
        double benchmarkClose = candles.getLast().close();
        double benchmarkQuantity = initialBalance / (benchmarkOpen * (1 + costPerSide));
        double benchmarkPnl = benchmarkQuantity * (benchmarkClose - benchmarkOpen)
            - benchmarkQuantity * costPerSide * (benchmarkOpen + benchmarkClose);
        return new BacktestResult(symbol, interval, candles.size(), WARMUP,
            initialBalance, equity, (equity / initialBalance - 1) * 100.0,
            100.0 * benchmarkPnl / initialBalance, drawdownPct,
            trades.size(), winners, trades.isEmpty() ? 0 : 100.0 * winners / trades.size(),
            List.copyOf(trades), List.of(
                "Educational only: long-spot, one position at a time, no real orders",
                "Next-candle open entry; stop first if same candle also touches target",
                "Fees and slippage are constant assumptions; worse live fills are possible",
                "Remaining open positions are liquidated at end-of-sample close",
                "Drawdown based on closed trades only; intratrade risk is not captured",
                "Short recent sample, no walk-forward test, no guarantee of profitability",
                "Buy-and-hold baseline is fully invested and is not risk matched"));
    }

    private record Position(long openMs, double entry, double quantity, double stop, double target) {}
}
