# Trading Mentor: Telegram and Market Automation Setup

Branch: `feat/scheduled-market-and-hermes-news` (stacked on PR #5).
All operations are **local-only** and **paper/research-only**.

## A. Verify your real Telegram channel delivery

Your token and chat ID exist on YOUR Mac, not in GitHub or ChatGPT.
The application MUST run from the branch containing PR #5.

```bash
git fetch origin
git switch feat/scheduled-market-and-hermes-news
cp -n .env.example .env    # safe if .env already exists
chmod 600 .env
${EDITOR:-nano} .env
```

Verify local `.env` has:
```dotenv
TELEGRAM_DELIVERY_ENABLED=true
TELEGRAM_TEST_ALERT_ENABLED=true
TELEGRAM_BOT_TOKEN='YOUR_REAL_SECRET'
TELEGRAM_CHAT_ID='@YOUR_REAL_CHANNEL'
MARKET_SCAN_ENABLED=false
HERMES_NEWS_INTAKE_ENABLED=false
```

Start Spring Boot:
```bash
set -a; source .env; set +a
./gradlew clean test
bash scripts/start-local.sh
```

In a second terminal:
```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/alerts/test
```

Copy the returned `eventId`. Query it (URL-encode if needed):
```bash
curl -sS 'http://127.0.0.1:8080/api/v1/alerts/status'
curl -sS 'http://127.0.0.1:8080/api/v1/alerts/events/connectivity-v1:YOUR_UUID'
```

The test is CONFIRMED only after `status=SENT` with a non-null
`telegramMessageId` AND you see the test post in YOUR intended channel.
`QUEUED` and `SENDING` are not proof of delivery.
Token/chat/channel cannot be accessed or checked by the repo alone.
If no post appears, inspect `FAILED` and the bot's **Post Messages**
channel admin permission. Do not post your bot token or chat ID into issues.

Set `TELEGRAM_TEST_ALERT_ENABLED=false` after success and restart.

## B. Automatically scan BTC/ETH (no Hermes job required)

Set in `.env`:
```dotenv
MARKET_SCAN_ENABLED=true
TELEGRAM_DELIVERY_ENABLED=true
TELEGRAM_TEST_ALERT_ENABLED=false
```

Restart `./gradlew bootRun`. The optional Java scheduler begins scanning
30 seconds after startup, then ~every 15 minutes following the previous scan.
It fetches *completed* BTCUSDT/ETHUSDT 4h candles and only queues qualifying
PAPER_SETUP_WATCH events. Their idempotency keys include pair, candle time
and strategy version, so no new setup means NO Telegram alert.

The dispatcher checks pending items about once a minute and requires an
actual Telegram Bot API acknowledgement before marking SENT.

**Critical: disable/delete older Hermes cron jobs that call /alerts/scan
or directly deliver setup messages.** The Java scheduler now owns scans
and Java owns Telegram delivery. Keep one owner per job.

## C. Enable Hermes AI-curated global news

Only enable after the Telegram connection test succeeds.

1. Add to local `.env` (generate a strong independent secret with
   `openssl rand -hex 32`):
```dotenv
HERMES_NEWS_INTAKE_ENABLED=true
HERMES_NEWS_INGEST_KEY='REPLACE_WITH_64_HEX_CHAR_RANDOM_SECRET'
```
2. Restart the backend with all `.env` variables loaded.
3. The JSON intake endpoint:
   `POST /api/v1/news/candidates`
   requires the `X-News-Intake-Key` header and exact schema.
   The local `scripts/submit-hermes-news.sh` reads the key privately from
   `.env`; never include the key in an agent prompt.
4. Read `docs/hermes-news-agent.md`. Ensure your Hermes installation has
   working browser/search AND local shell support.
5. Prepare a self-contained scheduled Hermes research job:
```bash
chmod +x scripts/submit-hermes-news.sh
hermes cron list
SCRIPT_PATH="$(pwd)/scripts/submit-hermes-news.sh"
PROMPT="$(sed "s|__SUBMIT_SCRIPT__|${SCRIPT_PATH}|g" docs/hermes-news-agent.md)"
hermes cron create 'every 1h' "$PROMPT" \
  --workdir "$(pwd)" --deliver local --name 'Trading Mentor Global Crypto Research'
```

Do **not** change `--deliver local` to `--deliver telegram` for this job.
Only the Java outbox may post to Telegram. If the agent cannot run a shell
command in your environment, the job is NOT operational: inspect its logs
and configure the proper tool capability before relying on it.

6. Verify results by running an actual Hermes research job and inspecting:
```bash
hermes cron list
curl -sS http://127.0.0.1:8080/api/v1/alerts/status
```

For an accepted candidate, intake returns its event ID and `QUEUED`;
duplicate event keys return `DUPLICATE` without resending.
The same `GET /api/v1/alerts/events/{eventId}` can confirm Telegram ACK.

## Limits and safety
- This is an automated AI **research candidate** pipeline; Java checks only
  URL hosts, timestamp bounds, payload shape and deduplication. It **cannot**
  verify source page contents, article truth or independent confirmation.
- Require factual source reading by Hermes and optionally human review for
  important claims; do not consider an AI candidate a verified trade signal.
- HTTP operator APIs remain bound to 127.0.0.1 without authentication.
  Never expose them via public IP or reverse proxy without auth and TLS.
- Existing Telegram outbox is **at least once**, not strictly exactly once.
- No real Binance account API keys, no exchange order execution, and no
  financial-performance or guaranteed-profit claims.
- Scheduler stops when your Mac is off/asleep. 24/7 needs a separately
  secured always-on host; do not deploy to production government servers.


## Verifying actual Telegram delivery

After POST /api/v1/alerts/test returns an eventId, check the exact event:

```bash
curl -sS 'http://127.0.0.1:8080/api/v1/alerts/events/connectivity-v1:YOUR_UUID'
```

A delivered status is `SENT` with a non-null `telegramMessageId`.
The recipient must also visually confirm the test post is in the **intended**
channel. API/CI success alone cannot prove your private channel received it.
Hermes browser/terminal capability and the gateway running locally are
additional requirements for an active news-research schedule.

## Startup safety on macOS (embedded H2)

The file-backed H2 database must be opened by only one Java application
process. Use `bash scripts/start-local.sh` rather than launching a second
`./gradlew bootRun` or IntelliJ instance against the same DB. The preflight
inspects `lsof` for `data/trading-mentor.mv.db` and port 8080, refuses a
conflicting start, and loads `.env` without printing credentials. It never
kills a process or deletes any data.

For the exact recovery commands, see
[H2 file lock troubleshooting](troubleshooting-h2-lock.md).
