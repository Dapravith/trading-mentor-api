#!/usr/bin/env bash
set -euo pipefail
if ! command -v hermes >/dev/null 2>&1; then
  echo "Hermes CLI not installed or not in PATH" >&2
  exit 1
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$HOME/.hermes/scripts"
TARGET="$HOME/.hermes/scripts/ai-trading-mentor-java-scan.sh"
printf '#!/usr/bin/env bash\nset -euo pipefail\nexec %q\n' "$ROOT/scripts/hermes-scan.sh" > "$TARGET"
chmod 700 "$TARGET"
echo "Installed scanner: $TARGET"
echo "Disable previous Hermes jobs that directly send Telegram alerts."
echo "Create one script-only scan job (no Hermes Telegram delivery):"
echo "hermes cron create 'every 15m' --no-agent --script ai-trading-mentor-java-scan.sh --name 'AI Trading Mentor Scan'"
echo "Configure Java TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID / TELEGRAM_DELIVERY_ENABLED=true for acknowledged sending."
