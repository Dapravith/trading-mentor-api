# Telegram Bot Commands

Control the mentor from a **private chat** with your notifications bot.
Everything is paper research: no command can place a real order.

## How it works
- The app long-polls the Bot API (`getUpdates`) on its own thread, so no
  public webhook is needed and the server stays bound to `127.0.0.1`.
- Only **private chats** from user IDs in `TELEGRAM_ALLOWED_USER_IDS` are
  executed. Anyone else gets `⛔ Not authorized` with their user ID; group and
  channel messages are ignored.
- Commands sent while the app was offline (older than 5 minutes) are not
  replayed; the bot asks you to send them again.
- `/open` and `/close` only show a **preview**. Nothing is written to the paper
  ledger until you send `/confirm` within 5 minutes. Restarting the app drops
  any pending preview.
- Replies are sent directly, not through the alert outbox. Market alerts
  still go to the alert channel through the durable outbox.

## Responsiveness and retries
- **Instant pickup.** Telegram's long poll returns the moment you send a
  message; the poller hands it off and immediately polls again.
- **"typing…" indicator.** Shown as soon as a command starts and refreshed
  every 4 s until the reply is sent, so slow commands such as `/research`
  visibly work instead of looking stuck.
- **No head-of-line blocking.** Read-only commands run concurrently, so `/help`
  or `/paper` answers right away even while a `/backtest` is still running.
  `/open`, `/close`, `/confirm` and `/cancel` run one at a time, in the order
  sent, so a confirmation can never overtake its preview.
- **Reply retries.** A failed reply is resent up to 4 times on transient
  errors (network, Telegram 5xx, 429) with exponential backoff and jitter
  (≈0.5 s, 1 s, 2 s), honouring Telegram's `retry_after` (capped at 30 s).
  Permanent errors (400/401/403/404, e.g. you blocked the bot) are not retried.
  Only the *reply* is resent — the command is never executed twice. A network
  failure after Telegram already accepted a reply can rarely produce a
  duplicate message.
- **Market-data retries.** `/scan`, `/scan_all`, `/queue_scan`, `/backtest`
  and `/research` retry a Binance failure up to 3 attempts (≈1 s, 2 s apart)
  before replying `Market data unavailable`. Input mistakes are reported
  immediately without retrying.
- **Polling recovery.** After a network blip the next poll is retried within
  about 0.5 s, backing off to 15 s if Telegram stays unreachable. A
  `POLLING_CONFLICT` (another poller/webhook) or rejected token waits 30 s.
  Log lines: `telegram_reply_retry`, `telegram_reply_recovered`,
  `telegram_reply_failed`, `telegram_command_market_retry`,
  `telegram_commands_poll_failed`, `telegram_commands_poll_recovered`.

## Setup
1. Use the **same notifications bot** as the alert channel
   ([telegram-outbox.md](telegram-outbox.md)). Do not enable this on a bot that
   Hermes or any other process already polls: Telegram allows a single
   `getUpdates` consumer and logs `telegram_commands_poll_failed code=POLLING_CONFLICT`.
2. In Telegram, open a private chat with the bot and press **Start**.
3. In `.env`:

```bash
TELEGRAM_COMMANDS_ENABLED=true
TELEGRAM_ALLOWED_USER_IDS='YOUR_NUMERIC_USER_ID'
```

   Don't know your ID? Start the app with any placeholder number, message the
   bot, and it replies with `Your Telegram user ID is …`. Put that value in
   `.env` and restart. Multiple IDs: `'111,222'`.
4. `set -a; source .env; set +a; ./gradlew bootRun`, then send `/help`.

Startup fails fast if commands are enabled without a bot token or allowed IDs.

## Commands

| Command | What it does |
| --- | --- |
| `/help`, `/start` | Full list with examples |
| `/status` | Delivery/scan modes, outbox counts, paper cash |
| `/scan [SYMBOL] [4h\|1d] [BALANCE]` | Analyse the last completed candle (does not queue an alert) |
| `/scan_all [4h\|1d]` | One-line overview of BTC and ETH |
| `/queue_scan` | Same as `POST /api/v1/alerts/scan`: queue qualifying setups for the channel |
| `/alerts` | Outbox counts: PENDING, SENDING, SENT, FAILED |
| `/event ID` | Delivery status and Telegram message ID of one alert |
| `/test_alert` | Queue a connection-test message (needs `TELEGRAM_DELIVERY_ENABLED=true`) |
| `/last_test` | Result of the latest connection test |
| `/backtest [SYMBOL] [4h\|1d] [BALANCE]` | Bounded educational replay |
| `/research [SYMBOL] [START] [END] [BALANCE]` | Chronological train/validate/test on daily candles |
| `/paper` | Paper balance and realized P&L |
| `/positions [open\|closed\|all]` | Up to 15 recent positions with short IDs |
| `/position ID` | Full position and journal |
| `/open SYMBOL ENTRY STOP TARGET EMOTION THESIS…` | Preview a paper entry with its risk plan |
| `/close ID EXIT_PRICE MANUAL\|STOP\|TARGET REFLECTION…` | Preview a paper exit |
| `/confirm` | Record the pending `/open` or `/close` |
| `/cancel` | Discard it |

Symbols may be written `BTC`/`ETH` or `BTCUSDT`/`ETHUSDT`. Position IDs accept
the first 4–8 characters shown by `/positions`. Emotions: `CALM`, `FOMO`,
`FEAR`, `IMPULSIVE`, `UNCERTAIN`. Same paper rules as the HTTP API: two open
positions at most, 2% daily / 5% weekly loss limits, 2:1 net reward/risk.

Example:

```
/open BTC 60000 58500 64000 CALM Retest of breakout level
/confirm
/positions open
/close 1a2b3c4d 63900 TARGET Followed the plan
/confirm
```
