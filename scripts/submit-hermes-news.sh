#!/usr/bin/env bash
set -euo pipefail
# Bridge for Hermes agent research -> local Spring Boot intake.
# Only news JSON is passed by Hermes; the secret stays in local .env.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: submit-hermes-news.sh /path/to/news-candidate.json" >&2
  exit 2
fi
if [[ ! -f "$ROOT/.env" ]]; then
  echo "Missing private .env; see docs/hermes-news.md" >&2
  exit 3
fi
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a
if [[ "${HERMES_NEWS_INTAKE_ENABLED:-false}" != "true" || ${#HERMES_NEWS_INGEST_KEY} -lt 32 ]]; then
  echo "News intake disabled or missing key" >&2
  exit 4
fi
# Do not print or forward the credential to the agent. Only a local loopback API.
curl --silent --show-error --fail-with-body --max-time 25 \
  --request POST http://127.0.0.1:8080/api/v1/news/candidates \
  --header 'Content-Type: application/json' \
  --header "X-News-Intake-Key: ${HERMES_NEWS_INGEST_KEY}" \
  --data-binary "@$1"
