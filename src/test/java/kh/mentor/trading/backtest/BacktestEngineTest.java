package kh.mentor.trading.backtest;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.market.Candle;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.StrategyEngine;
import org.junit.jupiter.api.Test;

class BacktestEngineTest {
    private final RiskPlanner risk = new RiskPlanner(.005, .20, .001, .001);

    private List<Candle> bars(double entryOpen, double high, double low) {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            double open = i == 60 ? entryOpen : 100;
            double top = i == 60 ? high : 101;
            double bottom = i == 60 ? low : 99;
            bars.add(new Candle(i * 1000L, i * 1000L + 999L,
                open, Math.max(Math.max(open,100),top),
                Math.min(Math.min(open,100),bottom),100,1000));
        }
        return bars;
    }

    private StrategyEngine singleSignal() {
        return new StrategyEngine(risk) {
            @Override public MarketAnalysis analyze(String symbol, String interval,
                List<Candle> candles, double balance) {
                boolean qualifying = candles.size() == 60;
                RiskPlan plan = qualifying ? risk.calculate(balance, 100, 98, 108) : null;
                return new MarketAnalysis(symbol, interval, candles.getLast().closeTimeMs(),
                    "",100,100,100,50,1,1,
                    qualifying ? "PAPER_SETUP_WATCH" : "NO_TRADE",
                    List.of("Fixture"), plan, "test", "test");
            }
        };
    }

    @Test void stopWinsWhenStopAndTargetBothTouched() {
        BacktestResult result = new BacktestEngine(singleSignal(),risk,.001,.001)
            .run("BTCUSDT","4h",bars(100,110,97),1000);
        assertEquals(1,result.completedTrades());
        assertEquals(PaperTrade.ExitReason.STOP,result.trades().getFirst().exitReason());
        assertEquals(98,result.trades().getFirst().exit(),1e-6);
        assertTrue(result.endingBalanceUsdt() < 1000);
        assertEquals(0,result.winningTrades());
    }

    @Test void targetIsReportedWithNetPositiveProfit() {
        BacktestResult result = new BacktestEngine(singleSignal(),risk,.001,.001)
            .run("BTCUSDT","4h",bars(100,110,99),1000);
        assertEquals(PaperTrade.ExitReason.TARGET,result.trades().getFirst().exitReason());
        assertTrue(result.trades().getFirst().netPnlUsdt() > 0);
    }

    @Test void nextOpenGapOutsidePlanPreventsEntry() {
        BacktestResult result = new BacktestEngine(singleSignal(),risk,.001,.001)
            .run("BTCUSDT","4h",bars(110,111,100),1000);
        assertEquals(0,result.completedTrades());
        assertEquals(1000,result.endingBalanceUsdt(),1e-6);
    }

    @Test void noSignalRemainsFlat() {
        StrategyEngine noSignal = new StrategyEngine(risk) {
            @Override public MarketAnalysis analyze(String symbol,String interval,
                List<Candle> history,double balance) {
                return new MarketAnalysis(symbol,interval,history.getLast().closeTimeMs(),
                    "",100,100,100,50,1,1,"NO_TRADE",
                    List.of("No setup"),null,"test","test");
            }
        };
        BacktestResult result = new BacktestEngine(noSignal,risk,.001,.001)
            .run("ETHUSDT","1d",bars(100,101,99),1000);
        assertEquals(0,result.completedTrades());
        assertEquals(0,result.winRatePct());
    }

    @Test void strategyCannotSeeTheEntryCandle() {
        List<Candle> sample = bars(100,110,97);
        java.util.concurrent.atomic.AtomicInteger inspected = new java.util.concurrent.atomic.AtomicInteger();
        StrategyEngine inspector = new StrategyEngine(risk) {
            @Override public MarketAnalysis analyze(String symbol, String interval,
                List<Candle> history, double balance) {
                // The current entry candle must not be included in the signal history.
                assertFalse(history.contains(sample.get(history.size())));
                inspected.incrementAndGet();
                return new MarketAnalysis(symbol, interval, history.getLast().closeTimeMs(),
                    "",100,100,100,50,1,1,"NO_TRADE",
                    List.of("No signal"),null,"test","test");
            }
        };
        new BacktestEngine(inspector,risk,.001,.001)
            .run("BTCUSDT","4h",sample,1000);
        assertEquals(sample.size() - BacktestEngine.WARMUP, inspected.get());
    }

    @Test void insufficientWarmupIsRejected() {
        assertThrows(IllegalArgumentException.class,() ->
            new BacktestEngine(singleSignal(),risk,.001,.001)
                .run("BTCUSDT","4h",bars(100,110,97).subList(0,60),1000));
    }
}
