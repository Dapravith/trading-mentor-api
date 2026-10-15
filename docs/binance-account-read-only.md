# Connect a Real Binance Spot Account (Read-Only) + Daily Cron

**Purpose:** Track *your own real Spot account* without granting trading,
withdrawal, or transfer permission to this application.

**Operational status:** Opt-in code exists in this PR; nothing connects to
your account until YOU create a restricted key and configure a private server.
A successful unit test does not prove your real Binance account was accessed.

## 1. Generate a dedicated Binance read-only API key

1. Sign into Binance through its official website; open **Account → API
   Management → Create API** (exact UI wording varies by region/account).
2. Create a new **system-generated HMAC** key for this monitor (label it
   `Trading Mentor Read Only`). Complete Binance's verification checks.
3. Select **Enable Reading ONLY**. Keep Spot & Margin Trading, withdrawals,
   futures, margin, internal/universal transfers, and other elevated scopes
   **disabled**. Never reuse a key used by another trading bot.
4. Where available, restrict the key to your **server's fixed outgoing IP**.
   GitHub-hosted Action runner IPs are not suitable for a static allowlist.
5. Keep the API key and secret **out of GitHub**, chat messages, CI logs,
   screenshots and support tickets. Rotate/revoke promptly on suspected leak.

Binance references:
- [Create API Keys](https://www.binance.com/en/support/faq/detail/360002502072)
- [Spot Account Information](https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints)
- [API Key Restrictions](https://developers.binance.com/en/docs/catalog/core-trading-wallet/api/rest-api/account)

The connector uses only TWO signed GET routes:
`/sapi/v1/account/apiRestrictions` and `/api/v3/account`.
It explicitly rejects an elevated key. **There is no POST /order method.**

## 2. Configure your private .env on Mac or private server

The existing backend already binds to `127.0.0.1:8080` by default. On an
always-on Docker host, Compose must keep that API port loopback-only.

```dotenv
BINANCE_ACCOUNT_MONITOR_ENABLED=true
BINANCE_READ_API_KEY='THE_HMAC_READ_ONLY_API_KEY'
BINANCE_READ_API_SECRET='THE_HMAC_READ_ONLY_API_SECRET'

# Optionally send a privacy-limited account connectivity report once/day
# at 09:00 Asia/Phnom_Penh. Requires a private Telegram user chat ID > 0.
BINANCE_ACCOUNT_DAILY_ENABLED=true
BINANCE_PRIVATE_CHAT_ID='YOUR_POSITIVE_NUMERIC_TELEGRAM_USER_ID'
TELEGRAM_BOT_TOKEN='YOUR_EXISTING_BOT_TOKEN'
```

The private chat must already have started the bot. Do not put a public
channel username or `-100...` channel chat ID in `BINANCE_PRIVATE_CHAT_ID`.
The startup validator rejects a negative/group channel ID.

The daily report includes *only* date, connectivity and the number of
nonzero Spot asset types plus BTC/ETH **presence booleans**. It deliberately
does **not** send exact balances, Binance UID, API keys, deposits, or orders
to Telegram, especially not to a public notification channel.

Stop your current backend with Ctrl+C, then:

```bash
bash scripts/start-local.sh
```

In another terminal verify your API key permissions / account connectivity:

```bash
curl -i http://127.0.0.1:8080/api/v1/binance/account/status
```

Example **redacted** response (example only):

```json
{
  "checkedAtUtc":"2026-10-15T03:00:00Z",
  "readOnly":true,
  "nonZeroAssetCount":2,
  "holdsBtc":true,
  "holdsEth":false,
  "holdsUsdt":true,
  "mode":"SPOT_READ_ONLY",
  "disclaimer":"No price conversion, BUY/SELL decision, or orders executed"
}
```

The actual API remains private/loopback-only; never route it through a
public reverse proxy without authentication and TLS.

## 3. Daily autonomous repeat — Java Spring Scheduler

```java
@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Phnom_Penh")
```

This performs one daily check at **09:00 Cambodia time**, but only if:

- `BINANCE_ACCOUNT_MONITOR_ENABLED=true`
- `BINANCE_ACCOUNT_DAILY_ENABLED=true`
- `BINANCE_READ_API_KEY`, `BINANCE_READ_API_SECRET`, Telegram bot token
  and a **private numeric** DM user ID are configured
- Spring Boot is running on a single always-on machine

The H2 table `binance_account_digest` records a Telegram message ID and
the local report date. This avoids repeat posts on ordinary application
restarts that same day. If Telegram accepts the report but Java crashes
before committing the receipt, a duplicate is still possible; this is
**at-least-once** delivery.

If the API/key is invalid, key scopes are overprivileged, network is down,
or the report cannot be sent, the job **fails closed**. It logs only a broad
exception category and never an API secret, balance or provider URL.

## 4. What the system can and cannot do

| Capability | Available |
|---|---|
| Read real Spot account non-zero asset presence | Yes, explicitly enabled |
| Deny elevated trading/transfer/withdrawal keys | Yes |
| Deliver daily private connectivity digest | Yes, explicitly enabled |
| Scheduled BTC/ETH rule-based ENTRY WATCH | Existing paper-only cloud monitor |
| Automatically fetch all global stock/FX markets | No |
| Value whole Binance portfolio reliably in USDT | No |
| Verify strategy profitability and risk in live trading | No |
| Actually buy/sell or manage real protective orders | **No** |

Your account's existing `canTrade` flag is **not** the same as an API key
having permission to trade. Always inspect the API restriction report.

## 5. Real-money autonomous trading is deliberately a separate milestone

Before even designing live order execution, complete [Issue #3](https://github.com/Dapravith/trading-mentor-api/issues/3)
on realistic walk-forward tests and [Issue #4](https://github.com/Dapravith/trading-mentor-api/issues/4)
on risk discipline. A later, separate system would require a pretrade
risk engine with manual approval, portfolio-level position reconciliation,
exchange-side protection, static-IP allowlisting, a kill switch, strict daily
loss limits and durable idempotency for orders. **Never enable withdrawals.**

### Deployment warning

GitHub Actions cron is **best effort**, may skip runs and is not a secure
home for a real account key in a public repo. Use your **own locked-down
single-instance Docker host** (see `compose.yaml`) for a 24/7 read-only
account monitor, not a public repository workflow. Your Mac-based job stops
when the Mac sleeps.

**Do not store real exchange credentials in this repository's GitHub
Actions Secrets** for the paper-signal cloud workflow. That workflow has no
reason to access your real account.
