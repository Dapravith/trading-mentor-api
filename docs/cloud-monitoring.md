# Cloud Market Monitoring — 24/7 Coverage Plan (Paper Only)

This milestone adds **two mutually exclusive operation modes**. Both use the
existing Java indicator, breakout and RiskPlanner code. Neither connects a
Binance account, places orders, shorts an asset or guarantees profitable trades.

## Signal contract and safety

**Currently covered:** Binance **public BTCUSDT / ETHUSDT spot** data, 4-hour
**completed** candles only. Global stocks, currencies, metals and derivatives
are **not integrated**, despite the broader roadmap name.

- 🟢 **PAPER ENTRY WATCH** — only when the existing trend, RSI, 20-candle
  breakout, relative volume and 2:1 net reward/risk rules pass. A message
  shows the *closed candle* reference entry, hypothetical position sizing,
  invalidation/stop level and take-profit/exit level.
- ⏸ **NO TRADE** — no qualifying setup. Published only as part of the *daily*
  market status, not every 15 minutes. This is a valid decision.
- 🔴 **EXIT LEVELS** — if you independently open a *paper* long position, the
  watch message specifies stop invalidation and a take-profit target. The
  cloud scanner **cannot determine whether you actually hold a position**,
  and cannot issue a portfolio-specific SELL execution recommendation.
- Stale or malformed source data **fails closed** (no entry signal).
- Paper loss budget and spot allocation are capped by the existing RiskPlanner
  (configured defaults 0.5% account risk and 20% allocation). The cloud
  publisher independently checks hard limits before notifying.
- This is a rule-based **hypothesis**, **not a price forecast or verified edge**.
  Research remains incomplete (see [Issue #3](../..//issues/3)).

### Mode A — GitHub Actions cloud monitoring (no VPS)

**Best-effort unattended monitoring**, not a hard 24/7 uptime guarantee.

1. Review the PR and merge it into \`main\` after CI passes. Scheduled actions
   run only on the default branch.
2. The public branch \`monitor-state\` already contains a starting
   \`monitor-state.json\`. It stores **only event keys and Telegram receipt
   IDs**, **no bot secrets, prices or message bodies**. Never delete this
   branch or rewrite the state while the workflow is enabled.
3. Go to **GitHub → Settings → Secrets and variables → Actions → Secrets**:
   create \`TELEGRAM_BOT_TOKEN\` and \`TELEGRAM_CHAT_ID\`. Do **not**
   copy trading/exchange account keys into GitHub.
4. Go to **Actions → Cloud Market Monitor (paper only) → Run workflow**.
   Keep **dry_run = true** initially. Inspect the run's Java fetch, generated
   signal preview, timestamp/risk validation and test results.
5. Once the dry run is successful, manually run with **dry_run = false**.
   Confirm one daily status message or new watch event in the correct channel.
   The state branch should show a commit with that Telegram receipt ID.
6. Enable the recurring schedule with the **Actions variable**:
   \`CLOUD_MARKET_MONITOR_ENABLED=true\` (Variables, *not* Secrets).
   If disabled/absent, scheduled runs **skip** rather than send messages.
7. Check workflow runs over multiple 15-minute windows and the daily status.
   The workflow commits each acknowledged event to \`monitor-state\` before
   proceeding to the next one; a duplicate candle should never be sent during
   ordinary reruns.
8. To stop immediately, set \`CLOUD_MARKET_MONITOR_ENABLED=false\`.
   For urgent incident response, also disable the workflow on GitHub.

**Workflow:** \`.github/workflows/cloud-market-monitor.yml\`
runs at minute **07, 22, 37 and 52 UTC**. It reuses
\`CloudMarketSnapshotRunner\` -> \`scripts/cloud-market-monitor.py\`.
It builds and executes read-only Java with a temporary in-memory H2 database,
then uses an isolated Git branch to preserve provider ACKs.

**Operational caveats:**
- Scheduled GitHub Actions may be **delayed, skipped or disabled**. Public repo
  schedules can be disabled after 60 days of inactivity. Your account's Actions
  policy, quotas and runner availability also apply. No response guarantee
  applies to the four-hour closing candle.
- If Telegram accepts a post but the state push fails, another run may resend
  that message. The monitor is **at least once**, not truly exactly once.
- If any market fails validation, the whole run fails before sending anything.
- The daily message is a **coverage heartbeat**, not a continuous external
  watchdog: missing messages should trigger a manual run-health check.
- The service will not run Hermes news research by itself. A Hermes cron on
  your Mac also stops when that machine is off; use a separate always-on
  research host if continuous news curation is required.
- The old local Java scheduler or Hermes direct Telegram jobs **must not**
  concurrently send to the same channel for the same markets: choose
  **one** production owner and disable the others.

### Mode B — genuinely continuous server operation (requires a host)

When you have an always-on Linux server with Docker Compose, deploy the
existing Spring Boot backend rather than the GitHub Actions periodic runner.

1. **First disable Mode A** by setting the repository variable to \`false\`.
2. Clone **main** on the server into a private directory.
3. Create a local \`.env\` from \`.env.example\`; set
   \`TELEGRAM_BOT_TOKEN\`, \`TELEGRAM_CHAT_ID\`,
   \`TELEGRAM_DELIVERY_ENABLED=true\`, \`MARKET_SCAN_ENABLED=true\`,
   \`TELEGRAM_TEST_ALERT_ENABLED=false\`. Use \`chmod 600 .env\`.
4. Launch **one** container with:

   \`\`\`bash
   docker compose up -d --build
   docker compose ps
   docker compose logs --tail=80 trading-mentor
   curl -fsS http://127.0.0.1:8080/api/v1/health
   curl -fsS http://127.0.0.1:8080/api/v1/alerts/status
   \`\`\`

5. The Docker named volume \`trading_data\` preserves the local H2 database
   across restarts. Do NOT use \`docker compose down -v\` unless you intend to
   delete the data. The container runs without root, has read-only root FS,
   a curl healthcheck and restart policy.

**This is single-instance operation**, not production-grade high availability.
A server is essential for continuous operation; this repo cannot provision
free always-on compute without your hosting account. The application binds
container-internally to 0.0.0.0 but the host port is restricted to
**127.0.0.1:8080**. Never publish the unauthenticated ops/H2 API on the
Internet. Before scaling or multi-host running, migrate H2 to a transactional
server database with backups, add proper auth and distributed job leases.

### Acceptance checklist

- [ ] CI green for the cloud monitor PR
- [ ] Mode A manually dry-runs with current public Binance data and no secrets
- [ ] Telegram token/chat ID set as GitHub Actions **secrets**
- [ ] Run with dry_run=false; channel actually receives message
- [ ] Event receipt persisted on monitor-state branch
- [ ] Repeat workflow run of same bar -> **no repeated entry signal**
- [ ] Invalid/stale public data -> **no signal**
- [ ] Daily NO_TRADE/status report arrives, with meaningful UTC timestamp
- [ ] Multiple scheduled runs observed; no conflicting local scheduler
- [ ] Delivery failure and state-push failure paths investigated
- [ ] If Mode B selected, cloud schedule turned off and H2 volume backed up
- [ ] Formal external uptime monitoring and market-provider redundancy planned

Follow [Issue #12](https://github.com/Dapravith/trading-mentor-api/issues/12)
for the overall implementation tracker. Continue backtest quality at [Issue #3]
(https://github.com/Dapravith/trading-mentor-api/issues/3), portfolio rules
and exit-monitoring at [Issue #4](https://github.com/Dapravith/trading-mentor-api/issues/4).
