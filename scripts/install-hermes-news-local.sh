#!/usr/bin/env bash
set -euo pipefail
# Installs one LOCAL-output Hermes research job. Java sends any actual Telegram news.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
NAME="Trading Mentor Global Crypto Research"

[[ -f .env ]] || { echo "Local .env missing" >&2; exit 2; }
grep -Eq '^HERMES_NEWS_INTAKE_ENABLED=true[[:space:]]*$' .env || {
  echo "Set HERMES_NEWS_INTAKE_ENABLED=true in your local .env" >&2; exit 3;
}
grep -Eq '^HERMES_NEWS_INGEST_KEY=' .env || {
  echo "Generate an independent HERMES_NEWS_INGEST_KEY locally" >&2; exit 3;
}
command -v hermes >/dev/null 2>&1 || {
  echo "Hermes CLI is not installed" >&2; exit 4;
}
hermes cron create --help 2>&1 | grep -Fq -- '--workdir' || {
  echo "Your Hermes CLI needs cron --workdir support" >&2; exit 5;
}
curl --fail --silent --show-error --max-time 8 \
  http://127.0.0.1:8080/api/v1/health >/dev/null || {
  echo "Start Spring Boot first" >&2; exit 6;
}
JOBS="$(hermes cron list)" || { echo "Hermes cron is unavailable" >&2; exit 7; }
if printf '%s\n' "$JOBS" | grep -Fq "$NAME"; then
  echo "Job already exists. Review 'hermes cron list'; refusing duplicate."
  exit 8
fi
[[ -f docs/hermes-news-agent.md && -f scripts/submit-hermes-news.sh ]] || {
  echo "Research agent prompt or submit script missing" >&2; exit 9;
}
PROMPT="$(sed "s|__SUBMIT_SCRIPT__|$ROOT/scripts/submit-hermes-news.sh|g" docs/hermes-news-agent.md)"
hermes cron create 'every 1h' "$PROMPT" \
  --workdir "$ROOT" \
  --deliver local \
  --name "$NAME"
echo "Hermes research job submitted. Verify with hermes cron list."
echo "Run once by ID with hermes cron run JOB_ID; inspect the output and the Java outbox."
echo "IMPORTANT: Do not enable separate Hermes Telegram delivery, which would duplicate notifications."
