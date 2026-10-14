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

## Setup (local development)
1. Disable any old Hermes cron job that directly delivers signal text.
2. Create a Telegram bot with BotFather; add it to the target channel/group.
3. Configure environment variables on the local machine or secret manager:

```bash
export TELEGRAM_BOT_TOKEN='your-secret-bot-token'
export TELEGRAM_CHAT_ID='your-channel-or-group-chat-id'
export TELEGRAM_DELIVERY_ENABLED=true
./gradlew bootRun
```

4. Trigger a scan while the backend runs:
```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/alerts/scan
curl -sS http://127.0.0.1:8080/api/v1/alerts/status
```

`/scan` queues only qualifying paper setups; often the queue remains empty.
A scheduled Java worker attempts delivery once per minute while enabled.
Inspect `PENDING`, `SENDING`, `SENT`, and `FAILED` counts.

The bot token must never be pasted into a code repository, AI prompt,
Telegram group, log, screenshot or support ticket. Rotate it if exposed.
The app is bound to loopback; this operator API has no user auth and MUST NOT
be published to the Internet until authentication, TLS and authorization exist.

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
