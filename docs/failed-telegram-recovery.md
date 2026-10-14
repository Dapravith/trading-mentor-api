# Failed Telegram Alerts: Inspect, Fix Cause, Retry One Event

The durable outbox deliberately does not resend terminal `FAILED` items.
That includes events with Telegram permanent rejection, expired lease and
exhausted attempts. Silently resetting them risks repeated posts or hiding
invalid credentials.

This patch adds **manual retry only**. It preserves past data and audit history.

## First verify the cause

Start the existing service with a valid Telegram bot token and chat ID.
Confirm the service is on and outbox status is accessible:

```bash
curl -sS http://127.0.0.1:8080/api/v1/health
curl -sS http://127.0.0.1:8080/api/v1/alerts/status
curl -i http://127.0.0.1:8080/api/v1/alerts/test/latest
```

If the latest test is `SENT` and has a positive `telegramMessageId`, then
Spring Boot successfully received and stored a Telegram API ACK.
A previous `FAILED` count does not mean the bot is currently down.

**Two independent sources of alerts:**
- **Market signals:** `MARKET_SCAN_ENABLED=true` on Java. Scans completed
  BTC/ETH candles, which may generate zero new messages because there is no
  strategy setup. Existing `SENT` events are intentionally never repeated.
- **Hermes news:** the Hermes cron job must actually complete a research run
  and submit a fresh validated event. A cron job in `failed` state will
  not generate an event for the Java outbox. Inspect `hermes cron runs JOB_ID`
  and the Hermes model/browser/terminal tools separately.

## Enable manual recovery on your own Mac

The API binds to `127.0.0.1`. Do NOT expose it on the public Internet.
The recovered event will be resent as a fresh *delivery attempt* to the
configured chat ID, with the same original payload. The operator must confirm
the channel issue is fixed BEFORE requesting this.

1. Generate an **independent** operator secret:

```bash
openssl rand -hex 32
```

2. Update only your local Git-ignored `.env`:

```dotenv
TELEGRAM_DELIVERY_ENABLED=true
ALERT_MANUAL_RETRY_ENABLED=true
ALERT_OPERATOR_KEY='YOUR_NEW_64_HEX_OPERATOR_SECRET'
```

3. Stop the old Java instance with Ctrl+C, and start one instance from
   the new branch:

```bash
git fetch origin
git switch fix/manual-telegram-failed-alert-recovery
git pull --ff-only
./gradlew clean test
bash scripts/start-local.sh
```

4. In a second terminal, **inspect failed metadata first**:

```bash
bash scripts/manage-failed-alerts.sh list
```

Example metadata only (not actual output):

```json
[
  {
    "eventId": "connectivity-v1:...",
    "strategyVersion": "connectivity-v1",
    "attempts": 5,
    "errorCode": "TELEGRAM_REJECTED",
    "createdAtMs": 1791968700000
  }
]
```

Review whether the event should be delivered again. **Permanent rejection**
usually requires fixing channel permissions, token/chat ID, or bot membership.
`NETWORK_ERROR`, `TELEGRAM_UNAVAILABLE`, `RATE_LIMITED` may need
network/provider checks. `UNEXPECTED_DELIVERY_ERROR` requires inspecting
the Java logs. An expired-lease error may indicate that Telegram actually
accepted the message before the process crashed, so look for duplicates first.

5. After checking the cause, deliberately retry **one** event:

```bash
bash scripts/manage-failed-alerts.sh retry 'EVENT_ID_FROM_LIST' \
  'Channel admin Post Messages permission fixed and verified'
```

Expect `QUEUED`; it does NOT claim the Telegram message has been delivered.
The existing Java dispatcher processes the pending event on its next tick.
Check the exact event ID with:

```bash
curl -sS 'http://127.0.0.1:8080/api/v1/alerts/events/EVENT_ID_FROM_LIST'
```

Successful delivery has `status=SENT` and a positive `telegramMessageId`.
Confirm the message in the intended Telegram channel.

### API

- `GET /api/v1/alerts/failed?limit=20` — lists no message bodies; required
  header `X-Alert-Operator-Key`.
- `POST /api/v1/alerts/failed/{eventId}/retry` — requires header and JSON
  `{"reason":"Operator describes the fix in 10-200 characters"}`. Returns
  `202 QUEUED`, `404` for unknown IDs, or `409` if not `FAILED`.
- Disabled by default. Both `ALERT_MANUAL_RETRY_ENABLED=true` and
  `TELEGRAM_DELIVERY_ENABLED=true` are required. Startup rejects missing or
  placeholder operator credentials.
- A successful manual retry transaction inserts into `alert_retry_audit`,
  preserving the previous error/attempt count and operator reason. It
  requeues exactly one `FAILED` event. `SENT` messages cannot be replayed.
- No automatic reset of `FAILED`, no bulk retries, no new notifications
  for plain `NO_TRADE` analysis, and no new news event from a failed Hermes
  cron without successful source research.

**Delivery remains at-least-once:** if Telegram accepts a message and Java
crashes before recording the ACK, the original channel may already contain
the post. Audit/check manually before retrying a prior FAILED event.
