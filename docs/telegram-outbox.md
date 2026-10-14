# Reliable Telegram Signal Delivery

## Why the change
The previous Hermes no-agent output was sent **after** the database recorded
deduplication, with no confirmation back to Spring Boot. A failed network
delivery could silently lose a signal. The replacement uses a **durable outbox**
and a direct Telegram Bot API sender because `sendMessage` returns a concrete
provider acknowledgement (message ID). Hermes remains the AI research agent
and can still run the read-only scan trigger, but it no longer sends the same
alert a second time.

## State transitions
`PENDING -> SENDING -> SENT` on a verified Telegram acknowledgement.
Transient failure: `SENDING -> PENDING` with bounded exponential backoff.
Terminal rejection / retry exhaustion: `SENDING -> FAILED`.
Crash / restart: `SENDING` with expired lease becomes `PENDING` or `FAILED`
based on the attempt limit.

Idempotency key: `strategy_version:symbol:interval:candle_close_ms`.
Each candidate is atomically claimed using status/attempt predicates.
Attempts default to five; delay starts at 30 seconds and caps at 30 minutes.

**Delivery guarantee:** At-least-once attempt, not exactly-once Telegram delivery.
If Telegram accepted a message but the application crashed before saving the
acknowledgement, the message may be resent after lease expiry. This is a
fundamental distributed-systems limitation without end-to-end idempotency
support from the provider.

## Configure a dedicated Telegram channel bot (macOS)

**This setup is not complete until YOU create the bot/channel and supply the
secret token and chat ID locally.** The repository cannot generate BotFather
credentials or infer your Telegram channel. Use a dedicated **notifications
bot**, separate from any interactive Hermes bot, to prevent competing pollers
and duplicated messages.

1. Open [@BotFather](https://t.me/BotFather) in Telegram. Send `/newbot`,
   choose a name and a unique username ending in `bot`, and copy the bot token.
   Never post that token in chat, GitHub, logs or screenshots.
2. Create a test Telegram **channel** (not a discussion group). In Channel
   Settings → Administrators → Add Administrator, add the bot and allow
   **Post Messages**. For a public channel, note its `@channel_username`.
3. For a **private** channel use its numeric chat ID, typically beginning
   with `-100`. The bot must be added as an administrator. A method to
   obtain this ID is to post a message to the channel and inspect the
   `channel_post.chat.id` from the Bot API `getUpdates` response. This only
   works if the bot receives that channel update and no webhook/other poller
   is consuming it. Do not call `getUpdates` on an active Hermes bot.
4. From the repo root, create local secrets from the checked-in example:

```bash
cp .env.example .env
chmod 600 .env
# Edit .env and replace BOTH placeholders.
# Never commit the .env file. It is gitignored.
${EDITOR:-nano} .env
```

5. Enable the read-only test path and Java dispatcher for your **test channel**:

```bash
set -a
source .env
set +a
./gradlew clean test
./gradlew bootRun
```

6. In another terminal, send one explicitly labeled, non-trading test:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/alerts/test
curl -sS http://127.0.0.1:8080/api/v1/alerts/status
```

A successful `POST /test` returns `QUEUED` immediately. The Java scheduler
(60-second fixed delay by default) picks it up; the status eventually becomes
`SENT` **only after** Telegram's successful Bot API acknowledgement.
The Telegram channel should display a message explicitly saying
`🧪 Connection test — Telegram is connected.` and `Test only — not a trading signal.`
If a transient error occurs, the event stays queued with bounded retries.
If it goes `FAILED`, check channel permissions, chat ID and bot token.

7. Once test delivery works, change `TELEGRAM_TEST_ALERT_ENABLED=false`
   in `.env` and restart the app. Keep `TELEGRAM_DELIVERY_ENABLED=true`
   to deliver real **paper research alerts**, not actual exchange orders.

8. Queue live research **hypotheses** (may queue zero if no strategy setup):

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/alerts/scan
curl -sS http://127.0.0.1:8080/api/v1/alerts/status
```

A successful market scan does NOT guarantee a message. It only generates a
paper alert for qualifying *completed* candles. The backend does not
automatically collect news; global news reporting is a separate future
integration.

9. If you want recurring market scans, create ONE Hermes script-only job
   using `scripts/install-hermes-scan.sh`, after inspecting existing jobs:

```bash
hermes cron list
bash scripts/install-hermes-scan.sh
```

Follow your installed Hermes CLI syntax. The script should invoke the local
`POST /api/v1/alerts/scan` endpoint, **without** using Hermes Telegram
delivery flags or printing alerts. Java is the only Telegram sender.

**Never expose these unauthenticated operator endpoints to the Internet.**
The default app listens only on `127.0.0.1`. Credentials are injected through
environment variables, not Java source code or Spring YAML secrets.

### Troubleshooting

| Symptom | Meaning / next action |
| --- | --- |
| 404 from `/alerts/test` | Both delivery and test-endpoint flags must be true; restart after updating env |
| 500 app startup | Bot token or chat ID is blank while delivery is enabled |
| Outbox `PENDING` | Worker disabled, not yet due, or retry backoff |
| Outbox `SENDING` | Sender currently working or awaiting lease expiry |
| Outbox `FAILED` | Permanent rejection or exhausted retries; verify bot/channel configuration |
| Outbox `SENT` but no visible channel post | Verify the intended chat ID and channel, and Bot API message ID; never infer that a human read the post |
| Zero queued after `/scan` | No qualifying completed-candle setup (normal) or same event already queued |
| Repeated posts | Disable old Hermes `--deliver telegram` jobs and investigate send-before-ACK crashes |

## Operations
- Duplicate scans do not enqueue the same setup twice.
- Invalid market data yields no new setup and an HTTP error.
- Inspect `/api/v1/alerts/status` for failed alerts.
- Dead messages require operator review; do not reset their status
  automatically because permanent errors may indicate configuration faults.
- Use a database backup before any manual changes to FAILED rows.
- Do not turn on an old Hermes `--deliver telegram` script that prints
  alerts: the Java outbox is the single delivery owner.

## Later reliability improvements
- Replace startup SQL init with versioned Flyway migrations and automated
  production schema backups.
- Structured metrics for event age, attempts, failures and acknowledgements.
- Telegram HTTP 429 Retry-After parsing with bounded provider-compliant delays.
- Credential rotation procedure, runbooks, and authenticated operator replay.
- Integration tests using a controllable mock Telegram HTTP server.
- HA storage for multi-worker/cloud deployments (PostgreSQL recommended).
