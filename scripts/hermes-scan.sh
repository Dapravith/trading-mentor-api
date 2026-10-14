#!/usr/bin/env bash
set -euo pipefail
# No Telegram bot token, Binance key, or account secrets are needed.
# Designed for Hermes --no-agent scheduled script delivery.
URL="http://127.0.0.1:8080/api/v1/alerts/scan"
OUTPUT=$(curl --silent --show-error --fail --max-time 40 --request POST "$URL")
if [[ -n "$OUTPUT" ]]; then
  printf '%s\n' "$OUTPUT"
fi
