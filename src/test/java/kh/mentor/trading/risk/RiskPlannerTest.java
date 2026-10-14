package kh.mentor.trading.risk;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RiskPlannerTest {
    private final RiskPlanner planner = new RiskPlanner(0.005, 0.20, 0.001, 0.001);

    @Test void riskAndAllocationCapped() {
        RiskPlan plan = planner.calculate(1000, 100, 98, 106);
        assertTrue(plan.positionUsdt() <= 200.0001);
        assertTrue(plan.estimatedLossUsdt() <= 5.0001);
        assertTrue(plan.quantity() > 0);
        assertTrue(plan.valid());
    }

    @Test void rejectsLowNetRewardRisk() {
        RiskPlan plan = planner.calculate(1000, 100, 98, 101);
        assertFalse(plan.valid());
    }

    @Test void rejectsInvalidStop() {
        assertThrows(IllegalArgumentException.class, () -> planner.calculate(1000, 100, 105, 110));
    }

    @Test void allocationIncludesEstimatedEntryCosts() {
        RiskPlanner planner = new RiskPlanner(.005, .01, .01, .02);
        RiskPlan plan = planner.calculate(1000, 100, 95, 140);
        double cash = plan.quantity() * plan.entry() * (1 + .01 + .02);
        assertTrue(cash <= 1000.000001);
    }

    @Test void rejectsUnsafeRiskConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RiskPlanner(.20, .20, .001, .001));
    }
}
