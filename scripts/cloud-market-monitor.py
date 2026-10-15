#!/usr/bin/env python3
"""Cloud paper-monitor: validates Java snapshots, deduplicates, and stores Bot API ACKs.

ONLY public market data / educational analysis; NEVER places an exchange order.
The GitHub Actions job checks out an isolated monitor-state branch for persistence.
One workflow run at a time is enforced by GitHub Actions concurrency, but Telegram
is at-least-once if acknowledgement succeeds just before a git-push failure.
"""
import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from urllib import request, error

UTC = dt.timezone.utc
ALLOWED_SYMBOLS = ("BTCUSDT", "ETHUSDT")
STRATEGY = "breakout-v1"
CANDLE_MAX_AGE_SECONDS = 10 * 60 * 60
STATE_SCHEMA = 1


class MonitorError(Exception):
    pass


def utc_now():
    return dt.datetime.now(UTC)


def parse_utc(value):
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("must be timezone aware")
        return parsed.astimezone(UTC)
    except (TypeError, ValueError, AttributeError) as exc:
        raise MonitorError("Invalid UTC timestamp") from exc


def validate_snapshot(snapshot, now=None):
    now = now or utc_now()
    if not isinstance(snapshot, dict) or snapshot.get("schema") != 1 \
            or snapshot.get("strategyVersion") != STRATEGY:
        raise MonitorError("Unsupported snapshot schema or strategy version")
    generated = parse_utc(snapshot.get("generatedAtUtc"))
    if abs((now - generated).total_seconds()) > 15 * 60:
        raise MonitorError("Snapshot is stale or time-skewed")
    rows = snapshot.get("analyses")
    if not isinstance(rows, list) or len(rows) != len(ALLOWED_SYMBOLS):
        raise MonitorError("Expected exactly two independently validated market scans")
    validated = {}
    for analysis in rows:
        if not isinstance(analysis, dict):
            raise MonitorError("Invalid analysis payload")
        symbol = analysis.get("symbol")
        if symbol not in ALLOWED_SYMBOLS or symbol in validated:
            raise MonitorError("Duplicate/unsupported market symbol")
        if analysis.get("interval") != "4h":
            raise MonitorError("Only completed 4h bars are supported")
        close_ms = analysis.get("candleCloseMs")
        if not isinstance(close_ms, int) or isinstance(close_ms, bool):
            raise MonitorError("Invalid candle timestamp")
        age = (now - dt.datetime.fromtimestamp(close_ms / 1000, UTC)).total_seconds()
        if age < 0 or age > CANDLE_MAX_AGE_SECONDS:
            raise MonitorError("Future or stale market candle; no alert may be sent")
        status = analysis.get("status")
        if status not in ("NO_TRADE", "PAPER_SETUP_WATCH"):
            raise MonitorError("Unknown analysis status")
        price = finite_positive(analysis.get("lastCompletedClose"), "reference close")
        finite_positive(analysis.get("atr14"), "ATR")
        rsi = finite_positive(analysis.get("rsi14"), "RSI")
        if rsi > 100:
            raise MonitorError("Invalid RSI")
        if status == "PAPER_SETUP_WATCH":
            risk = analysis.get("paperRisk")
            if not isinstance(risk, dict) or risk.get("valid") is not True:
                raise MonitorError("Paper setup missing valid risk plan")
            entry = finite_positive(risk.get("entry"), "entry")
            stop = finite_positive(risk.get("stop"), "stop")
            target = finite_positive(risk.get("target"), "target")
            budget = finite_positive(risk.get("paperBalanceUsdt"), "paper balance")
            exposure = finite_positive(risk.get("positionUsdt"), "paper exposure")
            loss = finite_positive(risk.get("estimatedLossUsdt"), "estimated loss")
            rr = finite_positive(risk.get("netRewardRisk"), "net R:R")
            finite_positive(risk.get("quantity"), "paper quantity")
            if not(stop < entry < target) or rr < 2.0 \
                    or loss > budget * 0.010001 or exposure > budget * 0.201 \
                    or abs(entry - price) > max(0.01, price * .001):
                raise MonitorError("Risk plan violates entry/stop/target or hard risk caps")
        validated[symbol] = analysis
    return validated


def finite_positive(value, field):
    if isinstance(value, bool) or not isinstance(value, (int, float)) \
            or not math.isfinite(value) or value <= 0:
        raise MonitorError("Invalid numeric field: " + field)
    return float(value)


def load_state(path):
    if not path.is_file():
        raise MonitorError("Missing persisted state branch/file; refuse duplicate-prone execution")
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        raise MonitorError("Unreadable persisted monitor state") from exc
    if state.get("schema") != STATE_SCHEMA or not isinstance(state.get("sent"), dict):
        raise MonitorError("Invalid persisted state schema")
    return state


def telegram_send(message, token, chat_id):
    if not token or not chat_id:
        raise MonitorError("Telegram credentials not configured")
    url = "https://api.telegram.org/bot" + token + "/sendMessage"
    payload = json.dumps({
        "chat_id": chat_id, "text": message, "disable_web_page_preview": True
    }).encode("utf-8")
    outgoing = request.Request(url, data=payload, headers={
        "Content-Type": "application/json"}, method="POST")
    try:
        with request.urlopen(outgoing, timeout=15) as response:
            status = response.status
            result = json.load(response)
    except error.HTTPError as exc:
        # NEVER log the exception URL because it contains the secret token.
        raise MonitorError("Telegram HTTP status " + str(exc.code)) from None
    except (OSError, ValueError, error.URLError):
        raise MonitorError("Telegram connection or response error") from None
    if status != 200 or result.get("ok") is not True:
        raise MonitorError("Telegram did not acknowledge delivery")
    mid = result.get("result", {}).get("message_id")
    if not isinstance(mid, int) or mid <= 0:
        raise MonitorError("Telegram acknowledgement lacks message_id")
    return mid


def write_state_and_push(path, state):
    parent = path.parent
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8",
                                     prefix=".monitor-", suffix=".tmp",
                                     dir=parent, delete=False) as file:
        temp = Path(file.name)
        json.dump(state, file, sort_keys=True, indent=2)
        file.write("\n")
    temp.replace(path)
    commands = [
        ["git", "-C", str(parent), "add", path.name],
        ["git", "-C", str(parent), "commit", "-m",
         "chore(monitor-state): persist Telegram receipt"],
        ["git", "-C", str(parent), "push", "origin", "HEAD:monitor-state"],
    ]
    for cmd in commands:
        process = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if process.returncode:
            raise MonitorError("State persistence failed; message may have arrived. "
                               "Inspect channel before retrying.") from None


def format_signal(analysis):
    risk = analysis["paperRisk"]
    event_close = dt.datetime.fromtimestamp(
        analysis["candleCloseMs"] / 1000, UTC).strftime("%Y-%m-%d %H:%M UTC")
    return (
        "🟢 PAPER ENTRY WATCH · " + analysis["symbol"] + " (4h)\n"
        "Closed candle: " + event_close + "\n"
        "Reference close: " + money(analysis["lastCompletedClose"]) + " USDT (NOT live)\n"
        "ENTRY: Wait for manual confirmation; no market order is triggered.\n"
        "EXIT if invalidated: stop " + money(risk["stop"]) + " USDT\n"
        "EXIT if target reached: " + money(risk["target"]) + " USDT\n"
        "Paper size: " + money(risk["positionUsdt"]) + " USDT"
        " | max modeled loss: " + money(risk["estimatedLossUsdt"]) + " USDT\n"
        "Estimated net R:R: " + f'{risk["netRewardRisk"]:.2f}' +
        " | RSI: " + f'{analysis["rsi14"]:.1f}' +
        " | volume: " + f'{analysis["volumeRatio"]:.2f}' + "x\n"
        "⚠️ Hypothetical spot setup. No BUY/SELL order, no verified profit."
    )


def money(value):
    return f"{value:,.2f}"


def format_daily(rows, now):
    messages = ["📋 DAILY PAPER MARKET CHECK · " + now.strftime("%Y-%m-%d UTC")]
    for symbol in ALLOWED_SYMBOLS:
        analysis = rows[symbol]
        direction = "ENTRY WATCH" if analysis["status"] == "PAPER_SETUP_WATCH" else "NO TRADE"
        reasons = analysis.get("rationale") or []
        reason = str(reasons[0])[:140] if reasons else "No independent trading instruction"
        messages.append(f"{symbol}: {direction} | last closed candle "
                        f"{money(analysis['lastCompletedClose'])} USDT\n"
                        f"Reason: {reason}")
    messages.append("Only BTC/ETH spot is covered (NOT all world markets). "
                    "Closed-candle educational analysis, not live executable prices. "
                    "No exchange orders placed.")
    return "\n\n".join(messages)


def execute(snapshot_path, state_path, dry_run=False, now=None, sender=telegram_send,
            persist=write_state_and_push, token=None, chat_id=None):
    now = now or utc_now()
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    analyses = validate_snapshot(snapshot, now)
    state = load_state(state_path)
    events = []
    for symbol in ALLOWED_SYMBOLS:
        analysis = analyses[symbol]
        if analysis["status"] != "PAPER_SETUP_WATCH":
            continue
        key = f"{STRATEGY}:{symbol}:4h:{analysis['candleCloseMs']}"
        if key not in state["sent"]:
            events.append((key, format_signal(analysis)))
    daily_key = "daily:" + now.strftime("%Y-%m-%d")
    if daily_key not in state["sent"]:
        events.append((daily_key, format_daily(analyses, now)))
    if dry_run:
        print("DRY RUN ONLY: " + str(len(events)) +
              " new paper-watch / daily events; no Telegram call and no state change")
        for key, body in events:
            print("Candidate: " + key + "\n" + body)
        return len(events)
    for key, body in events:
        mid = sender(body, token, chat_id)
        # Telegram accepted it; immediately persist receipt before moving to next event.
        state["sent"][key] = {"messageId": mid, "sentAtUtc": now.isoformat()}
        persist(state_path, state)
        print("Telegram ACK persisted: " + key + ", messageId=" + str(mid))
    print("Completed: " + str(len(events)) + " new Telegram messages")
    return len(events)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        execute(args.snapshot, args.state, args.dry_run,
                token=os.getenv("TELEGRAM_BOT_TOKEN"),
                chat_id=os.getenv("TELEGRAM_CHAT_ID"))
    except (MonitorError, OSError, ValueError) as exc:
        print("CLOUD_MONITOR_FAILED: " + str(exc), file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
