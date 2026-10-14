# Paper Portfolio and Discipline Journal — Issue #4

## Purpose
Track **manual hypothetical** BTCUSDT and ETHUSDT spot positions in a
persistent local H2 ledger. This is not Binance account integration, an order
executor, or a trustworthy measure of investable profit. Entry/exit prices are
entered by the learner and are not verified against market fills.

## Business rules implemented
- One virtual account initialized to 1,000 USDT (existing balance survives restarts).
- Manual approval required for opening and closing simulated positions.
- User-generated UUID `requestId` prevents duplicate paper entries.
- Deterministic RiskPlanner determines quantity, not the caller.
- Risk/reward >= 2:1 net of estimated transaction costs.
- Up to two simultaneous long spot positions.
- Virtual cash is reserved when opened and released with modeled proceeds
  when closed, with fees/slippage applied on each side.
- Block new entries after realized losing trades reach 2% daily or 5%
  weekly of the starting virtual balance (UTC).
- Journal entry thesis, emotion, exit reason, and retrospective reflection.
- Closure is idempotent and does not double-credit virtual cash.
- Uses Spring transactions and account-row locking on mutations.
- Spot only; no leverage, margin, exchange keys, automated trades or real funds.

## Example HTTP requests

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/paper/positions \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId":"f93fc229-98bb-4930-949a-bbf480b42d63",
    "symbol":"BTCUSDT",
    "entryPrice":100,
    "stopPrice":98,
    "targetPrice":110,
    "confirmPaperOnly":true,
    "thesis":"Educational bullish breakout hypothesis",
    "emotion":"CALM"
  }'

curl -sS http://127.0.0.1:8080/api/v1/paper/summary
curl -sS http://127.0.0.1:8080/api/v1/paper/positions
```

Close by replacing `POSITION_ID` with the returned ID:

```bash
curl -sS -X POST \
  http://127.0.0.1:8080/api/v1/paper/positions/POSITION_ID/close \
  -H 'Content-Type: application/json' \
  -d '{
    "exitPrice":105,
    "exitReason":"MANUAL",
    "reflection":"Followed my risk plan; review the results",
    "confirmPaperOnly":true
  }'
```

Supported emotions: CALM, FOMO, FEAR, IMPULSIVE, UNCERTAIN.
Exit reasons: MANUAL, STOP, TARGET.

## Limitations and future acceptance criteria
- No streaming mark-to-market quotes and no unrealized P&L; current summary
  reports available cash, reserved principal and realized P&L only.
- Virtual fills use learner-entered prices without Binance order book evidence.
- Losses can exceed planned stops; modeled limits are not real market protections.
- Single local H2 database, no account authentication or multiuser isolation.
- Floating point is used for learning only; convert finance quantities to
  BigDecimal and enforce asset precision before any live finance feature.
- No deposits, withdrawals, resets, audit event stream, weekly reports,
  periodic statement exports, dashboard or automated stop execution yet.
- Initial balance fixed at 1,000 USDT; use migration/explicit reset process
  to change it, never silently reset on app restart.
- Operator endpoints must remain bound to 127.0.0.1 until secure auth/TLS
  is implemented.

Issue #4 remains open until live-marked paper equity, immutable audit events,
weekly performance/rule-adherence reports, and fault/concurrency tests exist.
