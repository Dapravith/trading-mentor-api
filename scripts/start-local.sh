#!/usr/bin/env bash
set -euo pipefail

# Start one LOCAL Spring Boot process with the file-backed H2 database.
# Do not kill other processes, delete database files, or enable H2 AUTO_SERVER.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DB_FILE="$ROOT/data/trading-mentor.mv.db"

if command -v lsof >/dev/null 2>&1; then
  if [[ -f "$DB_FILE" ]]; then
    HOLDERS="$(lsof -nP "$DB_FILE" 2>/dev/null || true)"
    if [[ -n "$HOLDERS" ]]; then
      printf 'ERROR: The Trading Mentor H2 database is already open.\n' >&2
      printf 'Another process currently holds: %s\n\n' "$DB_FILE" >&2
      printf '%s\n\n' "$HOLDERS" >&2
      printf 'Stop the older app using Ctrl+C in its terminal, or inspect its PID before kill -TERM.\n' >&2
      printf 'Never delete the .mv.db file or start a second instance with the same DB.\n' >&2
      exit 73
    fi
  fi

  LISTENER="$(lsof -nP -iTCP:8080 -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$LISTENER" ]]; then
    printf 'ERROR: TCP port 8080 already has a listener.\n\n%s\n\n' "$LISTENER" >&2
    printf 'Check whether Trading Mentor is already running. Stop that instance before restarting.\n' >&2
    exit 73
  fi
else
  printf 'WARNING: lsof is unavailable; cannot check for an existing H2 lock or port listener.\n' >&2
fi

if [[ ! -x "$ROOT/gradlew" ]]; then
  printf 'ERROR: Gradle wrapper not executable; run chmod +x gradlew.\n' >&2
  exit 2
fi

if [[ -f "$ROOT/.env" ]]; then
  # Load the user's private bot/news configuration without printing secrets.
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

printf 'No visible H2 lock or port-8080 conflict. Starting one Spring Boot instance.\n'
exec "$ROOT/gradlew" bootRun "$@"
