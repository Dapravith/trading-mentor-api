package kh.mentor.trading.strategy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.market.Candle;
import kh.mentor.trading.risk.RiskPlanner;
import org.junit.jupiter.api.Test;

class StrategyEngineTest {
    private final StrategyEngine engine = new StrategyEngine(new RiskPlanner(.005, .20, .001, .001));

    @Test void flatMarketReturnsNoTrade() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            candles.add(new Candle(i*1000L, i*1000L+999, 100, 101, 99, 100, 1000));
        }
        MarketAnalysis result = engine.analyze("BTCUSDT", "4h", candles, 1000);
        assertEquals("NO_TRADE", result.status());
        assertNull(result.paperRisk());
        assertFalse(result.rationale().isEmpty());
    }

    @Test void tooFewCandlesFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> engine.analyze("BTCUSDT", "4h", List.of(), 1000));
    }
}
