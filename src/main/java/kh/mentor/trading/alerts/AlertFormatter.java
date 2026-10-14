package kh.mentor.trading.alerts;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.strategy.MarketAnalysis;

public final class AlertFormatter {
    private static final ZoneId CAMBODIA = ZoneId.of("Asia/Phnom_Penh");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);
    private AlertFormatter() {}

    public static String format(MarketAnalysis a) {
        RiskPlan risk = a.paperRisk();
        if (!"PAPER_SETUP_WATCH".equals(a.status()) || risk == null || !risk.valid()) {
            throw new IllegalArgumentException("No qualifying paper setup to announce");
        }
        String time = Instant.ofEpochMilli(a.candleCloseMs()).atZone(CAMBODIA).format(FORMAT);
        return """
            📊 AI TRADING MENTOR — PAPER SETUP ONLY
            Pair: %s | Interval: %s
            Last COMPLETED candle (Cambodia): %s
            Historical candle close: %,.4f USDT (NOT live quote)
            RSI14: %.2f | EMA20: %,.2f | EMA50: %,.2f
            Volume / previous 20 average: %.2fx
            Hypothetical stop: %,.4f | target: %,.4f
            Paper position value: %,.2f USDT
            Estimated downside with modeled costs: %,.2f USDT
            NOT a verified BUY signal. No real orders. Check current market conditions.
            """.formatted(a.symbol(), a.interval(), time, a.lastCompletedClose(),
                a.rsi14(), a.ema20(), a.ema50(), a.volumeRatio(),
                risk.stop(), risk.target(), risk.positionUsdt(), risk.estimatedLossUsdt());
    }
}
