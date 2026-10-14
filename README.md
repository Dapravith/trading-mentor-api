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

The project intentionally does **not** have actual BUY/SELL endpoints, Binance account API integration, automated trading, backtesting, an LLM-dependent research agent, or a live price feed. These belong to later phases after rigorous testing.

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

```bash
./gradlew clean test
./gradlew bootRun
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

## Step 3 — Test deduplicated alerts locally

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/alerts/scan
```

- When both pairs have no qualifying setup (or all setups were previously recorded), the response is HTTP **204** without a body.
- With a new paper setup, the response is HTTP **200**, text-only, including Cambodia-local candle time.
- If a market data fetch fails, it returns an error; no actionable signal is issued.

*Limitation:* The dedupe record is persisted **before Hermes/Telegram acknowledges delivery**, so a failed send may lose an alert. Treat it as an educational at-most-once publisher; later harden with an outbox + delivery acknowledgements before calling it production-ready.

## Step 4 — Hermes integration

First keep the backend running. Validate your Hermes Telegram gateway and destination.

```bash
hermes gateway status
hermes cron list
bash scripts/install-hermes-scan.sh
```

**Important:** If the earlier Python trading mentor is already scheduled, disable/delete that cron job first to avoid two separate bots sending duplicate alerts. Inspect the list and follow Hermes CLI commands for your installed version.

Then register only one job (after confirming Hermes CLI supports the shown cron flags):

```bash
hermes cron create 'every 15m' \
  --no-agent \
  --script ai-trading-mentor-java-scan.sh \
  --deliver telegram \
  --name 'AI Trading Mentor Java Paper Watch'
```

The Hermes script `scripts/hermes-scan.sh` uses `curl` against the local Spring Boot endpoint and prints output only for fresh educational setup alerts. Telegram delivery is handled by Hermes. This script is not an autonomous LLM trading system.

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
