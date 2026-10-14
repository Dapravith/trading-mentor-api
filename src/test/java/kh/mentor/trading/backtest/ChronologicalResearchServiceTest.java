package kh.mentor.trading.backtest;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.market.Candle;
import kh.mentor.trading.market.HistoricalMarketDataProvider;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.StrategyEngine;
import org.junit.jupiter.api.Test;

class ChronologicalResearchServiceTest {
    private static final long DAY = 86_400_000L;

    @Test void splitsChronologicallyAndReturnsDeterministicSnapshot() {
        long start = LocalDate.parse("2024-01-01")
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            long open = start + DAY * i;
            bars.add(new Candle(open, open + DAY - 1,
                100, 101, 99, 100, 1000));
        }
        HistoricalMarketDataProvider provider = (s, from, to) -> {
            assertEquals("BTCUSDT", s);
            assertEquals(start, from);
            assertEquals(start + 400L * DAY, to);
            return List.copyOf(bars);
        };
        RiskPlanner risk = new RiskPlanner(.005, .20, .001, .001);
        ChronologicalResearchService svc =
            new ChronologicalResearchService(provider, new StrategyEngine(risk),
                risk, .001, .001);
        String endDate = LocalDate.ofEpochDay(
            LocalDate.parse("2024-01-01").toEpochDay() + 400).toString();
        var first=svc.run("BTCUSDT", "2024-01-01", endDate, 1000);
        var second=svc.run("BTCUSDT", "2024-01-01", endDate, 1000);
        assertEquals(first.datasetSha256(), second.datasetSha256());
        assertEquals(400, first.datasetCandles());
        assertEquals(200, first.training().completedCandles());
        assertEquals(160, first.validation().completedCandles());
        assertEquals(160, first.testing().completedCandles());
        assertEquals(0, first.testing().completedTrades());
    }

    @Test void invalidWindowRejectedBeforeRequestingData() {
        HistoricalMarketDataProvider unused = (s, from, to) ->
            { throw new AssertionError("Must reject before contacting Binance"); };
        RiskPlanner risk=new RiskPlanner(.005,.20,.001,.001);
        var service=new ChronologicalResearchService(unused,
            new StrategyEngine(risk), risk,.001,.001);
        assertThrows(IllegalArgumentException.class, () ->
            service.run("ETHUSDT","2025-01-01","2025-04-01",1000));
        assertThrows(IllegalArgumentException.class, () ->
            service.run("DOGEUSDT","2024-01-01","2026-01-01",1000));
    }
}
