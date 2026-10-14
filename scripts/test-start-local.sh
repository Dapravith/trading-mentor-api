#!/usr/bin/env bash
set -euo pipefail
# Exercise the start-local preflight with fake lsof/Gradle; never touch a real DB.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
PROJ="$TEMP/project"
mkdir -p "$PROJ/scripts" "$PROJ/data" "$TEMP/bin"
cp "$ROOT/scripts/start-local.sh" "$PROJ/scripts/start-local.sh"
touch "$PROJ/data/trading-mentor.mv.db"
cat > "$PROJ/gradlew" <<'GRADLE'
#!/usr/bin/env bash
printf 'GRADLE_STARTED: %s\n' "$*" >> "$TEST_GRADLE_LOG"
GRADLE
chmod +x "$PROJ/gradlew"
cat > "$TEMP/bin/lsof" <<'LSOF'
#!/usr/bin/env bash
if [[ "$*" == *"trading-mentor.mv.db"* ]]; then
  if [[ "${TEST_H2_MODE:-}" == "locked" ]]; then
    printf 'COMMAND PID USER FD TYPE NAME\njava 4242 local 18u REG trading-mentor.mv.db\n'
    exit 0
  fi
  exit 1
fi
if [[ "$*" == *"-iTCP:8080"* ]]; then
  if [[ "${TEST_PORT_MODE:-}" == "busy" ]]; then
    printf 'COMMAND PID USER FD TYPE NAME\njava 4343 local 4u IPv4 TCP *:8080 (LISTEN)\n'
    exit 0
  fi
  exit 1
fi
exit 1
LSOF
chmod +x "$TEMP/bin/lsof"
export PATH="$TEMP/bin:$PATH"
export TEST_GRADLE_LOG="$TEMP/gradle.log"
bash -n "$ROOT/scripts/start-local.sh"

if TEST_H2_MODE=locked bash "$PROJ/scripts/start-local.sh" >"$TEMP/out" 2>&1; then
  echo 'FAIL: should refuse an H2 lock' >&2; exit 1
fi
grep -q 'H2 database is already open' "$TEMP/out"
[[ ! -e "$TEST_GRADLE_LOG" ]] || { echo 'FAIL: Gradle launched under a lock' >&2; exit 1; }

if TEST_PORT_MODE=busy bash "$PROJ/scripts/start-local.sh" >"$TEMP/out" 2>&1; then
  echo 'FAIL: should refuse an occupied port' >&2; exit 1
fi
grep -q 'port 8080' "$TEMP/out"
[[ ! -e "$TEST_GRADLE_LOG" ]] || { echo 'FAIL: Gradle launched with port occupied' >&2; exit 1; }

bash "$PROJ/scripts/start-local.sh" >"$TEMP/out" 2>&1
grep -q 'GRADLE_STARTED: bootRun' "$TEST_GRADLE_LOG"
echo 'Startup preflight safety tests passed'
