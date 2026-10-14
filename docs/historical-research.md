# Multi-Year Chronological Research (Issue #3 — foundation)

## Usage
```bash
./gradlew bootRun
curl -sS 'http://127.0.0.1:8080/api/v1/backtests/chronological?symbol=BTCUSDT&start=2022-01-01&end=2026-01-01&startingBalance=1000'
```

Supported pairs: BTCUSDT/ETHUSDT; UTC daily candles only; 360–2000 days.
The Binance public endpoint is paginated, checked for contiguous timestamps,
valid OHLCV, verified server time and *fully completed* candles. The
deterministic dataset SHA-256 identifies the exact price snapshot.

Results have **three separate portfolios**, each starting with the supplied
hypothetical balance:
- First 50%: in-sample training-period observation (no parameter fitting).
- Next 25%: chronological validation-period observation.
- Final 25%: holdout test-period observation, not to be tuned against.

The existing strategy and risk planner remain unchanged. The engine receives
only preceding completed bars and enters at the next open. Validation/test
include 60 previous bars solely for indicator warm-up.

## Important limits
This is *NOT* a full walk-forward parameter optimization system.
It uses fixed strategy parameters and three chronological segments.
The 500-bar backtest already has other known limitations: constant fee/slippage,
closed-trade-only drawdown, stop-first OHLC assumptions, no depth/slippage model,
no volatility-adjusted benchmarks, and no statistical inference.

Issue #3 remains open until rolling retraining, full equity curve and
mark-to-market drawdown, realistic liquidity/order-minimum constraints,
immutable persisted datasets and rigorous out-of-sample reporting exist.
Never interpret these results as a live trade instruction.
