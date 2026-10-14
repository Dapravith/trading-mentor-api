#!/usr/bin/env bash
set -euo pipefail
# Deterministic verification using a fake curl; never contacts real Telegram.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'MOCK'
#!/usr/bin/env bash
case "$*" in
  */api/v1/health*) echo '{"status":"UP"}' ;;
  */api/v1/alerts/test*)
    echo '{"status":"QUEUED","eventId":"connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}' ;;
  */api/v1/alerts/events/*)
    echo '{"eventId":"connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","status":"SENT","attempts":1,"telegramMessageId":12345}' ;;
  *) echo 'unexpected URL' >&2; exit 1 ;;
esac
MOCK
chmod +x "$TMP/bin/curl"
OUTPUT="$(PATH="$TMP/bin:$PATH" bash "$ROOT/scripts/verify-telegram-delivery.sh")"
printf '%s\n' "$OUTPUT" | grep -Fq 'Database acknowledgement verified. Telegram message_id=12345'
echo 'Telegram acknowledgement checker smoke test passed'
