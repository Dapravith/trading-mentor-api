#!/usr/bin/env bash
set -euo pipefail
# Hermes may trigger scans, but the Java outbox worker owns Telegram delivery.
# Intentionally print nothing to stdout to avoid duplicate Hermes channel messages.
curl --silent --show-error --fail --max-time 40 \
  --request POST http://127.0.0.1:8080/api/v1/alerts/scan >/dev/null
