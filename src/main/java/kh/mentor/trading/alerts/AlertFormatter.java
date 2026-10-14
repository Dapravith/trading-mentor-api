package kh.mentor.trading.alerts;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.strategy.MarketAnalysis;

/** Short, plain-text Telegram notifications. Every message keeps a one-line paper-only notice. */
public final class AlertFormatter {
    private static final ZoneId CAMBODIA = ZoneId.of("Asia/Phnom_Penh");
    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("d MMM HH:mm z", Locale.ENGLISH);
    private AlertFormatter() {}

    public static String format(MarketAnalysis a) {
        RiskPlan risk = a.paperRisk();
        if (!"PAPER_SETUP_WATCH".equals(a.status()) || risk == null || !risk.valid()) {
            throw new IllegalArgumentException("No qualifying paper setup to announce");
        }
        return """
            📊 %s %s · Paper setup
            Close %s · RSI %.1f · Vol %.1fx · Trend %s
            Stop %s → Target %s · R:R %.1f
            Size %s USDT · Risk ≈%s USDT
            🕒 Candle closed %s
            ⚠️ Paper only — not a buy signal, no orders placed."""
            .formatted(a.symbol(), a.interval(),
                price(a.lastCompletedClose()), a.rsi14(), a.volumeRatio(),
                a.ema20() > a.ema50() ? "↑" : "↓",
                price(risk.stop()), price(risk.target()), risk.netRewardRisk(),
                price(risk.positionUsdt()), price(risk.estimatedLossUsdt()),
                time(a.candleCloseMs()));
    }

    /** Cambodia time, e.g. "14 Oct 15:00 ICT"; shared by all notifications. */
    public static String time(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(CAMBODIA).format(FORMAT);
    }

    private static String price(double value) {
        return String.format(Locale.ROOT, "%,.2f", value);
    }
}
