package kh.mentor.trading.alerts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.MarketAnalysis;
import org.junit.jupiter.api.Test;

class AlertFormatterTest {
    static MarketAnalysis setup(String status, double ema20, double ema50) {
        RiskPlan plan = new RiskPlanner(.005, .20, .001, .001).calculate(1000, 100, 98, 110);
        return new MarketAnalysis("BTCUSDT", "4h", 1_700_000_000_000L,
            "2023-11-14T22:13:20Z", 64250.123, ema20, ema50, 58.34, 1, 1.42,
            status, List.of("Educational"), plan, "fixture", "NO REAL ORDERS");
    }

    @Test void setupAlertIsShortAndKeepsPaperOnlyNotice() {
        RiskPlan plan = setup("PAPER_SETUP_WATCH", 2, 1).paperRisk();
        String expected = """
            📊 BTCUSDT 4h · Paper setup
            Close 64,250.12 · RSI 58.3 · Vol 1.4x · Trend ↑
            Stop 98.00 → Target 110.00 · R:R %.1f
            Size %s USDT · Risk ≈%s USDT
            🕒 Candle closed 15 Nov 05:13 ICT
            ⚠️ Paper only — not a buy signal, no orders placed.""".formatted(
            plan.netRewardRisk(), String.format(java.util.Locale.ROOT, "%,.2f", plan.positionUsdt()),
            String.format(java.util.Locale.ROOT, "%,.2f", plan.estimatedLossUsdt()));
        String message = AlertFormatter.format(setup("PAPER_SETUP_WATCH", 2, 1));
        assertEquals(expected, message);
        assertTrue(message.lines().count() <= 6);
    }

    @Test void downTrendArrowFollowsEmas() {
        assertTrue(AlertFormatter.format(setup("PAPER_SETUP_WATCH", 1, 2)).contains("Trend ↓"));
    }

    @Test void nonQualifyingAnalysisIsNeverAnnounced() {
        assertThrows(IllegalArgumentException.class,
            () -> AlertFormatter.format(setup("NO_TRADE", 2, 1)));
    }

    @Test void timeIsCambodiaLocal() {
        assertEquals("15 Nov 05:13 ICT", AlertFormatter.time(1_700_000_000_000L));
    }
}
