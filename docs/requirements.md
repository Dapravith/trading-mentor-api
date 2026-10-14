# AI Trading Mentor — Business Requirements

Status: Draft · Mode: educational research and paper trading

## Product goal
Help a beginner understand market data, risk, patience, execution costs, and
strategy evaluation. Neither Hermes nor the Java API may autonomously trade
real funds. No claims of investment advice or guaranteed profit.

## User roles
- Learner: inspects analyses, runs historical simulations, journals paper trades.
- Operator: configures monitoring, maintains data quality and alerts.
- Hermes assistant (future): explains validated signals and news with citations.
  It is prohibited from overriding deterministic risk checks.

## Requirements and acceptance criteria

| ID | Requirement | Acceptance criterion | Stage |
| --- | --- | --- | --- |
| BR-001 | Public market data | BTCUSDT/ETHUSDT, closed contiguous candles, reject stale inputs | Existing |
| BR-002 | Reproducible signals | Same candles/config => same NO_TRADE or PAPER_SETUP_WATCH | Existing |
| BR-003 | Simulated position risk | Position size respects max loss and allocation INCLUDING entry costs | Enhanced |
| BR-004 | Historical replay | Next-bar open entry; no look-ahead; stop-first intrabar ambiguity | This PR |
| BR-005 | Performance measurements | Net P&L, drawdown, win rate, fee/slippage and baseline comparison | This PR |
| BR-006 | Paper journal | Persist simulated orders, reflections, reason codes and equity history | Future |
| BR-007 | Reliable Telegram alerts | Pending/delivered/failed outbox with acknowledgement and retry | Future |
| BR-008 | Verified market news | De-duplicated credible sources, UTC event and publication timestamps | Future |
| BR-009 | Real-money safety | Explicit approval and regulatory review before any exchange order capability | Future |

## Non-functional requirements
- Fail closed on unavailable or malformed market data.
- UTC storage; use Cambodian local time only for presentation.
- No exchange credentials, withdrawal permissions or order endpoints in this version.
- Localhost binding until authentication, TLS and network controls exist.
- Pure deterministic simulation separate from the public API and data adapter.
- Bounded computation; test edge cases; CI on pull requests.
- Risk limits are enforced in Java, never through LLM instructions alone.
- Record strategy version, data provenance and configuration before scaling.
- Separate audit logs, failure monitoring and observability before production.

## Backtest restrictions
The first replay is recent historical research with at most 500 completed bars,
including 60 warm-up bars. That is about 83 days for 4-hour candles.
It cannot validate multiple market cycles or establish profitability.
No look-ahead means the latest completed bar may inform only the next bar.
When both stop and target are crossed within one OHLC bar, assume STOP first.
Constant fees and slippage understate risks during fast or illiquid markets.
Gap-through-stop orders are filled at the worse bar opening price.
Open positions are marked closed at the end of the sample to compare outcomes.
Drawdown is calculated on closed trades only, so intratrade loss may be larger.
Buy-and-hold uses 100% allocation and is not risk matched.

## Release gates
CI must pass; use an independent review of backtest fill assumptions.
No automatic trading or copy-paste investment recommendations.
