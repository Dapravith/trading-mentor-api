package kh.mentor.trading.alerts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class MarketScanSchedulerTest {
    @Test void scansWithoutDirectTelegramDelivery() {
        AlertScanService scanning=mock(AlertScanService.class);
        when(scanning.scanAndQueue()).thenReturn(1);
        new MarketScanScheduler(scanning).scan();
        verify(scanning,times(1)).scanAndQueue();
    }

    @Test void binanceFailuresFailClosedAndDoNotCrashScheduler() {
        AlertScanService scanning=mock(AlertScanService.class);
        when(scanning.scanAndQueue()).thenThrow(new IllegalStateException("upstream unavailable"));
        assertDoesNotThrow(()->new MarketScanScheduler(scanning).scan());
    }
}
