package kh.mentor.trading.market;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CandleTest {
    @Test void validCandleAccepted() {
        assertEquals(100, new Candle(1000,1999, 100, 104, 99, 101, 10).open());
    }

    @Test void invalidHighIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Candle(1000,1999, 100, 98, 99, 101, 10));
    }

    @Test void negativeVolumeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Candle(1000,1999, 100, 104, 99, 101, -1));
    }
}
