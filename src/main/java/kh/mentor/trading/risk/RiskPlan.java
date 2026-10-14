package kh.mentor.trading.risk;

import java.util.List;

public record RiskPlan(double paperBalanceUsdt, double entry, double stop, double target,
    double quantity, double positionUsdt, double estimatedLossUsdt,
    double estimatedRewardUsdt, double netRewardRisk, boolean valid, List<String> reasons) {}
