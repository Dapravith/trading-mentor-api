#!/usr/bin/env bash
set -euo pipefail
# Fake curl handles JSON responses, status codes and -o/-w flags. No Telegram calls.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'MOCK'
#!/usr/bin/env bash
out_file=""
print_code=false
url=""
while (( $# )); do
  case "$1" in
    -o|--output) out_file="$2"; shift 2 ;;
    -w|--write-out) print_code=true; shift 2 ;;
    -X|--request|--max-time) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
case "$url" in
  */api/v1/health*) body='{"status":"UP"}'; code=200 ;;
  */api/v1/alerts/test*)
    body='{"status":"QUEUED","eventId":"connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}'
    code="${FAKE_TEST_HTTP_CODE:-202}" ;;
  */api/v1/alerts/events/*)
    body='{"eventId":"connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","status":"SENT","attempts":1,"telegramMessageId":12345}'
    code=200 ;;
  *) echo 'unexpected URL' >&2; exit 1 ;;
esac
if [[ -n "$out_file" ]]; then printf '%s' "$body" > "$out_file"
else printf '%s\n' "$body"; fi
if [[ "$print_code" == true ]]; then printf '%s' "$code"; fi
MOCK
chmod +x "$TMP/bin/curl"
OUTPUT="$(PATH="$TMP/bin:$PATH" bash "$ROOT/scripts/verify-telegram-delivery.sh")"
printf '%s\n' "$OUTPUT" | grep -Fq 'Database acknowledgement verified. Telegram message_id=12345'

if ERROR_OUTPUT="$(FAKE_TEST_HTTP_CODE=500 PATH="$TMP/bin:$PATH" bash "$ROOT/scripts/verify-telegram-delivery.sh" 2>&1)"; then
  echo "A mocked HTTP 500 must fail the checker" >&2
  exit 1
fi
printf '%s\n' "$ERROR_OUTPUT" | grep -Fq 'server/database error, NOT proof of wrong Telegram flags'
echo 'Telegram acknowledgement verifier and HTTP 500 diagnostic tests passed'
