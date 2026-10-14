package kh.mentor.trading.strategy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import kh.mentor.trading.market.Candle;
import org.junit.jupiter.api.Test;

class IndicatorsTest {
    @Test void constantPricesHaveNeutralRsi() {
        assertEquals(50, Indicators.rsi(java.util.Collections.nCopies(30, 100.0), 14), 0.00001);
    }

    @Test void upOnlyClosesHaveMaximumRsi() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 30; i++) closes.add(100.0 + i);
        assertEquals(100, Indicators.rsi(closes, 14), 0.00001);
    }

    @Test void emaOfConstantSeriesRemainsConstant() {
        assertEquals(10, Indicators.ema(java.util.Collections.nCopies(60, 10.0), 50), 0.00001);
    }

    @Test void atrOfEqualTrueRanges() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candles.add(new Candle(i*1000L, i*1000L+999, 100, 101, 99, 100, 1000));
        }
        assertEquals(2, Indicators.atr(candles, 14), 0.00001);
    }

    @Test void insufficientHistoryFails() {
        assertThrows(IllegalArgumentException.class, () -> Indicators.rsi(List.of(1.0), 14));
    }
}
