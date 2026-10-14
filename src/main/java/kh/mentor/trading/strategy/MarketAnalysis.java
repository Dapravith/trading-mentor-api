package kh.mentor.trading.strategy;

import java.util.List;
import kh.mentor.trading.risk.RiskPlan;

public record MarketAnalysis(
    String symbol,
    String interval,
    long candleCloseMs,
    String candleCloseUtc,
    double lastCompletedClose,
    double ema20,
    double ema50,
    double rsi14,
    double atr14,
    double volumeRatio,
    String status,
    List<String> rationale,
    RiskPlan paperRisk,
    String source,
    String disclaimer
) {}
