#!/usr/bin/env bash
set -euo pipefail
# This sends ONE explicitly labelled test via local Spring Boot, not via direct Bot API.
# Java owns delivery and persists Telegram's ACK; never print local credentials.

BASE="http://127.0.0.1:8080"
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 required" >&2
  exit 2
fi
curl --fail --silent --show-error --max-time 10 "$BASE/api/v1/health" >/dev/null || {
  echo "Spring Boot is not responding on 127.0.0.1:8080" >&2
  exit 3
}
RESPONSE="$(curl --fail --silent --show-error --max-time 15 -X POST "$BASE/api/v1/alerts/test")" || {
  echo "Could not queue: check TELEGRAM_DELIVERY_ENABLED and TELEGRAM_TEST_ALERT_ENABLED in .env, then restart." >&2
  exit 4
}
EVENT_ID="$(printf '%s' "$RESPONSE" | python3 -c '
import json, re, sys
try:
    value=json.load(sys.stdin)["eventId"]
    assert re.fullmatch(r"connectivity-v1:[0-9a-f-]{36}",value)
    print(value)
except (AssertionError, ValueError, KeyError, TypeError):
    print("Invalid test endpoint response",file=sys.stderr)
    sys.exit(2)
')"
echo "Queued test event: $EVENT_ID"
for (( i=1; i<=30; i++ )); do
  BODY="$(curl --fail --silent --show-error --max-time 10 "$BASE/api/v1/alerts/events/$EVENT_ID")" || exit 5
  STATUS="$(printf '%s' "$BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("status","UNKNOWN"))')"
  echo "Delivery status: $STATUS"
  case "$STATUS" in
    SENT)
      RECEIPT="$(printf '%s' "$BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("telegramMessageId") or "")')"
      if ! [[ "$RECEIPT" =~ ^[1-9][0-9]*$ ]]; then
        echo "SENT without valid Telegram message ID" >&2; exit 6
      fi
      echo "Database acknowledgement verified. Telegram message_id=$RECEIPT"
      echo "Now confirm the test message appears in the intended channel."
      exit 0
      ;;
    FAILED)
      echo "Delivery failed: inspect channel permissions and outbox status; never expose bot token." >&2
      exit 7
      ;;
    PENDING|SENDING) ;;
    *) echo "Unknown event state: $STATUS" >&2; exit 8 ;;
  esac
  if (( i < 30 )); then sleep 5; fi
done
echo "No provider acknowledgement yet. Inspect /api/v1/alerts/status." >&2
exit 9
