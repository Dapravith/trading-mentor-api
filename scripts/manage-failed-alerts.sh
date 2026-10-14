#!/usr/bin/env bash
set -euo pipefail

# Work with FAILED Telegram outbox events via the protected loopback API.
# Only explicit operator requests may retry one FAILED event at a time.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f .env ]]; then
  echo "Local .env missing" >&2; exit 2
fi
set -a
# shellcheck disable=SC1091
source .env
set +a
if [[ "${ALERT_MANUAL_RETRY_ENABLED:-false}" != "true" ]]; then
  echo "Set ALERT_MANUAL_RETRY_ENABLED=true in local .env and restart Java" >&2
  exit 3
fi
if [[ ! "${ALERT_OPERATOR_KEY:-}" =~ ^[A-Za-z0-9_-]{32,128}$ ]] ||
   [[ "${ALERT_OPERATOR_KEY}" == REPLACE* ]]; then
  echo "ALERT_OPERATOR_KEY missing/invalid; generate with openssl rand -hex 32" >&2
  exit 3
fi
command -v python3 >/dev/null 2>&1 || {
  echo "python3 required for safe JSON encoding" >&2; exit 3;
}
case "${1:-}" in
  list)
    # Curl -K reads the secret from standard input, not the process command line.
    printf 'header = "X-Alert-Operator-Key: %s"\n' "$ALERT_OPERATOR_KEY" |
      curl --config - --silent --show-error --fail-with-body --max-time 20 \
        'http://127.0.0.1:8080/api/v1/alerts/failed?limit=20' |
      python3 -m json.tool
    ;;
  retry)
    [[ $# -eq 3 ]] || {
      echo "Usage: bash scripts/manage-failed-alerts.sh retry EVENT_ID 'Reason >=10 chars'" >&2; exit 2;
    }
    EVENT_ID="$2"
    if [[ ! "$EVENT_ID" =~ ^[A-Za-z0-9:._-]{1,160}$ ]]; then
      echo "Invalid event ID" >&2; exit 2
    fi
    JSON="$(python3 -c 'import json,sys;print(json.dumps({"reason":sys.argv[1]}))' "$3")"
    printf 'header = "X-Alert-Operator-Key: %s"\n' "$ALERT_OPERATOR_KEY" |
      curl --config - --silent --show-error --fail-with-body --max-time 20 \
        -X POST -H 'Content-Type: application/json' \
        --data-binary "$JSON" \
        "http://127.0.0.1:8080/api/v1/alerts/failed/$EVENT_ID/retry" |
      python3 -m json.tool
    echo "Requeued ONE event. Dispatcher checks pending events each minute by default."
    ;;
  *)
    echo "Usage: bash scripts/manage-failed-alerts.sh {list|retry EVENT_ID 'Reason'}" >&2
    exit 2
    ;;
esac
