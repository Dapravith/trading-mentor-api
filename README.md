# AI Trading Mentor — Java Spring Boot / Gradle

**Version:** Phase 1 (educational, read-only market analysis)  
**Stack:** Java 21, Spring Boot 4.1.1, Gradle 9.6.0, Spring MVC, JDBC, embedded H2  
**Default:** loopback-only `127.0.0.1:8080`, Binance public Spot data, BTCUSDT and ETHUSDT.  
**No user exchange credentials, order placement, Telegram token, leverage or live-trading functionality.**

## What this replaces

This is the Java Spring Boot migration of the earlier Python proof of concept, not a Python sidecar. It retains:

- Validated Binance public `/api/v3/time` and `/api/v3/klines` market data, with incomplete candles excluded.
- EMA20, EMA50, RSI14, ATR14, a prior-20-candle breakout check and volume confirmation.
- `NO_TRADE` or `PAPER_SETUP_WATCH` (neither is a verified trade instruction).
- Simulated long-spot risk estimates after assumed fees and slippage.
- H2 alert deduplication keyed by `(symbol, interval, candle close)`.
- A no-LLM Hermes script that sends an alert only for a newly qualifying paper setup.

The project has no actual BUY/SELL endpoints, Binance account API integration, or live trading. Backtesting is educational. Optional Hermes news research submits structured AI-curated candidates, not independently fact-checked alerts or trade instructions.

## Step 1 — Prerequisites (macOS)

```bash
java -version
# Java 21+ supported by Spring Boot 4.1.1; build is pinned to a Java 21 toolchain.

brew install gradle
# If you use SDKMAN or already have a suitable Gradle installation, use that instead.
gradle --version
```

Java 21 is preferred to avoid unexpected toolchain differences. Java 25 may run Gradle, but the build requires an installed Java 21 toolchain; install it if necessary (for example `brew install --cask temurin@21`).

> **Gradle wrapper:** This GitHub repository includes `gradlew` and
> `gradle/wrapper/gradle-wrapper.jar`. You do not need to install Gradle
> globally. Use `./gradlew` directly. The wrapper currently targets Gradle 9.6.0.

## Step 2 — Test and start

**macOS startup fix:** A single embedded H2 file may be opened by only one
process. If `Database may be already in use [90020]` appears, see the
[non-destructive H2 lock troubleshooting guide](docs/troubleshooting-h2-lock.md).
**Do not delete the database file or force-kill unrelated Java processes.**


```bash
./gradlew clean test
# Preflight detects a running instance/locked H2 database (non-destructive).
bash scripts/start-local.sh
```

Open a separate terminal:

```bash
curl -sS http://127.0.0.1:8080/api/v1/health
curl -sS 'http://127.0.0.1:8080/api/v1/market/scan?symbol=BTCUSDT&interval=4h&paperBalance=1000'
curl -sS 'http://127.0.0.1:8080/api/v1/market/scan?symbol=ETHUSDT&interval=4h&paperBalance=1000'
```

The last completed candle's close is **not** a live executable price. The service returns errors rather than emitting analysis on bad/stale data.

Sample status keys:

```json
{
  "symbol": "BTCUSDT",
  "interval": "4h",
  "status": "NO_TRADE",
  "rationale": ["No close above previous 20-candle high"],
  "paperRisk": null
}
```

The sample above is schematic: the real response includes timestamp and indicators, with actual statuses based on fetched market data.

## Step 3 — Connect a Telegram channel (manual account setup)

The Java service now uses a durable Telegram outbox, and includes an
**opt-in, non-trading connection test**.

**Follow the complete [BotFather → Channel → Test guide](docs/telegram-outbox.md).**

```bash
cp .env.example .env
chmod 600 .env
# Fill in the token and channel destination on your computer.
${EDITOR:-nano} .env

set -a
source .env
set +a
./gradlew bootRun
```

Then run this in another terminal:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/alerts/test
curl -sS http://127.0.0.1:8080/api/v1/alerts/status
```

With both `TELEGRAM_DELIVERY_ENABLED=true` and
`TELEGRAM_TEST_ALERT_ENABLED=true`, the test queues a visible Telegram
**CONNECTION TEST**, not a trading recommendation. The Java worker retries
transient failures and marks `SENT` only after Telegram API acknowledgement.
After confirming your private test channel receives it, disable the test
endpoint by setting `TELEGRAM_TEST_ALERT_ENABLED=false` and restarting.

The scan endpoint remains `POST /api/v1/alerts/scan`, which only queues
qualifying **paper research** setups. No market setup means no signal message.
Hermes can trigger the scan, but must not deliver a second copy of the alert.
No Binance account credentials, trade execution, or automatic news-feed
Telegram posting exist in this version.

## Architecture

```text
Public Binance Spot Data (/time, /klines)
              |
              v
   market/BinancePublicClient
              |
              v
  strategy/ScanService -> Indicators + StrategyEngine
              |                 |
              |                 v
              |            risk/RiskPlanner
              v
      REST /market/scan
              |
          manual learner

POST /alerts/scan -> AlertScanService -> H2 dedupe -> text output
                                                |
                                          Hermes no-agent cron
                                                |
                                            Telegram
```

Source layout:

```text
src/main/java/kh/mentor/trading/
  TradingMentorApplication.java
  api/       MarketController, AlertController, ApiExceptionHandler
  market/    BinancePublicClient, Candle, MarketDataProvider
  strategy/  Indicators, StrategyEngine, ScanService, MarketAnalysis
  risk/      RiskPlanner, RiskPlan
  alerts/    AlertScanService, AlertRepository, AlertFormatter
src/main/resources/
  application.yml, schema.sql
src/test/java/...  JUnit 5 tests
scripts/           Hermes runner and installer
```

## Security constraints

- Spring Boot binds to `127.0.0.1` by default. **Do not change to `0.0.0.0` without adding authentication, network allowlisting, and HTTPS.**
- Binance market-data host is statically restricted to `https://data-api.binance.vision`.
- Only BTCUSDT / ETHUSDT and 4h / 1d are accepted.
- No exchange API keys are needed or should be added to this version.
- Signals are hypotheses, not verified historical profitability.
- For live operation, research the rules for exchange access applicable to your location.

## Phase 2: Bounded educational backtest

The new read-only endpoint simulates the current strategy on up to 500 recent
completed Binance candles. It uses only completed signal bars, enters at the
**next bar open**, sizes through RiskPlanner, assumes stop-first on intrabar
ambiguity, models fees and slippage, and ends the replay by liquidating open
positions for reporting. Results include net return, win rate, closed-trade
drawdown and a fully invested buy-and-hold comparator.

```bash
curl -sS 'http://127.0.0.1:8080/api/v1/backtests/run?symbol=BTCUSDT&interval=4h&startingBalance=1000'
```

This is a **short, in-sample educational simulation**, not evidence that a
strategy is profitable or a live buy/sell recommendation. The historical window
is insufficient for market-cycle coverage or walk-forward validation. Review
[Business Requirements and Limitations](docs/requirements.md).

GitHub Actions now runs the Gradle test suite on pull requests.

## Next phases

1. Add integration tests against stubbed Binance HTTP responses, as well as a full CI build.
2. Add offline candle fixtures, walk-forward backtesting and realistic fee/spread/slippage models.
3. Add paper portfolio and trade journal, including drawdown, rule adherence and comparisons to buy-and-hold.
4. Add a read-only Hermes LLM assistant that explains *validated* signals and cites news, without overriding risk checks.
5. Harden reliability (WebSocket reconnection if needed, delivery outbox, structured logs, Prometheus/Grafana).
6. Evaluate real-money readiness, regulatory permissions and strict manual execution approval; no autonomous order placement by default.

Official references:
- Spring Boot: https://docs.spring.io/spring-boot/reference/
- Gradle wrapper: https://docs.gradle.org/current/userguide/gradle_wrapper.html
- Binance public endpoints: https://github.com/binance/binance-spot-api-docs/blob/master/faqs/market_data_only.md

## Automated market monitoring and Hermes global news (PR #8)

Read [docs/automation-setup.md](docs/automation-setup.md) to complete local setup.
Both automation features are off by default.

- `MARKET_SCAN_ENABLED=true` schedules Java scans of completed BTC/ETH 4h candles every 15 minutes. The same outbox ensures one alert per setup. Disable any older Hermes market-scan job.
- `GET /api/v1/alerts/events/{eventId}` exposes the actual Telegram provider acknowledgement and message ID, without exposing message contents.
- `HERMES_NEWS_INTAKE_ENABLED=true` enables a loopback-only, independently secret-gated news intake. Hermes can research market-moving events and submit vetted metadata by calling `scripts/submit-hermes-news.sh`.
- Hermes research jobs deliver **locally**, never directly to Telegram; Java is the single delivery owner for all alerts.
- The research intake validates source domains, freshness, event identity, and mandatory risk/impact fields. **It does not verify page contents independently.** Messages are labeled AI-curated.

This automation runs only while Spring Boot and the configured Hermes gateway are online.
No live exchange API keys or trading endpoints are included.

## Three remaining local acceptance checks

The BotFather token and chat ID are stored only in your local `.env`, not
in this GitHub repository. To finish operator setup:

1. With `TELEGRAM_DELIVERY_ENABLED=true` and
   `TELEGRAM_TEST_ALERT_ENABLED=true`, start one backend via
   `bash scripts/start-local.sh`, then in another terminal run
   `bash scripts/verify-telegram-delivery.sh`. This creates a test message,
   polls its persisted event and verifies `SENT` and Telegram `message_id`.
   Also visually check the intended channel.
2. Change `.env` to `MARKET_SCAN_ENABLED=true` and
   `TELEGRAM_TEST_ALERT_ENABLED=false`, restart the backend. Java owns
   the 15-minute completed-candle scan; disable older Hermes market crons.
3. Configure a strong `HERMES_NEWS_INGEST_KEY`, set
   `HERMES_NEWS_INTAKE_ENABLED=true`, restart the backend and run
   `bash scripts/install-hermes-news-local.sh` in a second terminal.
   The installer checks the endpoint and refuses an existing job.

See [Complete local activation runbook](docs/automation-setup.md).
Neither CI success nor registration of a cron job proves that your local
Hermes browser or Telegram destination has been exercised end-to-end.
