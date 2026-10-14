package kh.mentor.trading.risk;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Educational spot sizing, not an exchange order sizing calculator. */
@Component
public class RiskPlanner {
    private final double riskFraction, maxAllocation, feePerSide, slippagePerSide;

    public RiskPlanner(
        @Value("${trading.risk-fraction:0.005}") double riskFraction,
        @Value("${trading.max-allocation:0.20}") double maxAllocation,
        @Value("${trading.fee-per-side:0.001}") double feePerSide,
        @Value("${trading.slippage-per-side:0.001}") double slippagePerSide) {
        this.riskFraction = riskFraction;
        this.maxAllocation = maxAllocation;
        this.feePerSide = feePerSide;
        this.slippagePerSide = slippagePerSide;
        if (!(riskFraction > 0 && riskFraction <= 0.01 && maxAllocation > 0 && maxAllocation <= 1 &&
              feePerSide >= 0 && feePerSide <= 0.01 && slippagePerSide >= 0 && slippagePerSide <= 0.02) ||
            !Double.isFinite(riskFraction) || !Double.isFinite(maxAllocation) ||
            !Double.isFinite(feePerSide) || !Double.isFinite(slippagePerSide)) {
            throw new IllegalArgumentException("Unsafe risk configuration");
        }
    }

    public RiskPlan calculate(double paperBalance, double entry, double stop, double target) {
        if (!Double.isFinite(paperBalance) || !Double.isFinite(entry) || !Double.isFinite(stop) ||
            !Double.isFinite(target) || paperBalance <= 0 || paperBalance > 1_000_000_000 ||
            !(0 < stop && stop < entry && entry < target)) {
            throw new IllegalArgumentException("Invalid paper position values");
        }
        double costRate = feePerSide + slippagePerSide;
        double downsidePerUnit = entry - stop + costRate * (entry + stop);
        double rewardPerUnit = target - entry - costRate * (entry + target);
        double quantity = Math.min(paperBalance * riskFraction / downsidePerUnit,
            paperBalance * maxAllocation / entry);
        double loss = quantity * downsidePerUnit;
        double reward = quantity * rewardPerUnit;
        double rr = loss > 0 ? reward / loss : 0;
        List<String> reasons = new ArrayList<>();
        if (rr < 2.0) reasons.add("Estimated net reward/risk below 2:1");
        if (reward <= 0) reasons.add("Reward after modeled costs is not positive");
        return new RiskPlan(paperBalance, entry, stop, target, quantity,
            quantity * entry, loss, reward, rr, reasons.isEmpty(), List.copyOf(reasons));
    }
}
