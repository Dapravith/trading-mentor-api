package kh.mentor.trading.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.UUID;

import kh.mentor.trading.market.CurrentPriceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:paper-service-tests;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
@Import(PaperPortfolioServiceTest.TestConfig.class)
@Transactional
class PaperPortfolioServiceTest {

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        CurrentPriceProvider testCurrentPriceProvider() {
            return Mockito.mock(CurrentPriceProvider.class);
        }
    }

    @Autowired
    PaperPortfolioService service;

    @Autowired
    CurrentPriceProvider currentPriceProvider;

    @BeforeEach
    void setupMarketPrices() {
        reset(currentPriceProvider);

        when(currentPriceProvider.fetchCurrentPrice("BTCUSDT"))
            .thenReturn(100.0);

        when(currentPriceProvider.fetchCurrentPrice("ETHUSDT"))
            .thenReturn(100.0);
    }

    private OpenPaperRequest buy() {
        return new OpenPaperRequest(
            UUID.randomUUID().toString(),
            "BTCUSDT",
            100, 98, 110,
            true,
            "Pullback in a tested paper setup",
            "CALM"
        );
    }

    @Test
    void paperEntryRequiresExplicitApproval() {
        OpenPaperRequest request = new OpenPaperRequest(
            UUID.randomUUID().toString(),
            "BTCUSDT",
            100, 98, 110,
            false,
            "Test setup",
            "CALM"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.open(request)
        );

        assertEquals(
            1000,
            service.summary().availableCashUsdt(),
            0.000001
        );
    }

    @Test
    void duplicateRequestDoesNotSpendCashTwice() {
        OpenPaperRequest request = buy();

        PaperPosition first = service.open(request);
        double remaining = service.summary().availableCashUsdt();

        PaperPosition repeat = service.open(request);

        assertEquals(first.id(), repeat.id());

        assertEquals(
            remaining,
            service.summary().availableCashUsdt(),
            1e-6
        );

        assertEquals(1, service.summary().openPositions());
    }

    @Test
    void closingRestoresVirtualCashAndRecordsJournal() {
        PaperPosition entry = service.open(buy());

        assertTrue(entry.quantity() > 0);
        assertTrue(service.summary().availableCashUsdt() < 1000);

        PaperPosition closed = service.close(
            entry.id(),
            new ClosePaperRequest(
                110,
                "TARGET",
                "Waited for rules",
                true
            )
        );

        assertEquals("CLOSED", closed.status());
        assertTrue(closed.netPnlUsdt() > 0);
        assertEquals("Waited for rules", closed.reflection());

        assertEquals(0, service.summary().openPositions());
        assertEquals(1, service.summary().closedPositions());

        assertEquals(
            1000 + closed.netPnlUsdt(),
            service.summary().availableCashUsdt(),
            1e-6
        );

        assertEquals(
            closed.netPnlUsdt(),
            service.close(
                entry.id(),
                new ClosePaperRequest(
                    110,
                    "TARGET",
                    "Waited for rules",
                    true
                )
            ).netPnlUsdt(),
            1e-6
        );
    }

    @Test
    void concurrentPositionLimitEnforced() {
        service.open(buy());
        service.open(buy());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.open(buy())
        );

        assertEquals(2, service.summary().openPositions());
    }

    @Test
    void hittingDailyLossLimitBlocksNewPaperEntries() {
        PaperPosition entry = service.open(buy());

        service.close(
            entry.id(),
            new ClosePaperRequest(
                1,
                "STOP",
                "Wrong model; paper loss",
                true
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> service.open(buy())
        );
    }

    @Test
    void invalidRiskSetupIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.open(
                new OpenPaperRequest(
                    UUID.randomUUID().toString(),
                    "BTCUSDT",
                    100, 105, 110,
                    true,
                    "Unsafe stop",
                    "FOMO"
                )
            )
        );

        assertEquals(0, service.summary().openPositions());
    }

    @Test
    void openPositionIsMarkedUsingCurrentMarketPrice() {
        PaperPosition entry = service.open(buy());

        when(currentPriceProvider.fetchCurrentPrice("BTCUSDT"))
            .thenReturn(110.0);

        PaperSummary summary = service.summary();

        double estimatedExitValue =
            entry.quantity() * 110.0 * (1 - 0.002);

        double expectedUnrealizedPnl =
            estimatedExitValue - entry.entryCashUsdt();

        assertEquals("AVAILABLE", summary.valuationStatus());

        assertNotNull(summary.unrealizedPnlUsdt());
        assertNotNull(summary.markedEquityUsdt());

        assertEquals(
            expectedUnrealizedPnl,
            summary.unrealizedPnlUsdt(),
            1e-6
        );

        assertEquals(
            summary.availableCashUsdt() + estimatedExitValue,
            summary.markedEquityUsdt(),
            1e-6
        );

        assertNotNull(summary.marketPriceObservedAtMs());
    }

    @Test
    void marketPriceFailureDoesNotReportFalsePortfolioEquity() {
        service.open(buy());

        when(currentPriceProvider.fetchCurrentPrice("BTCUSDT"))
            .thenThrow(new IllegalStateException("Simulated market outage"));

        PaperSummary summary = service.summary();

        assertEquals("UNAVAILABLE", summary.valuationStatus());
        assertNull(summary.unrealizedPnlUsdt());
        assertNull(summary.markedEquityUsdt());
        assertNull(summary.marketPriceObservedAtMs());
    }
}
