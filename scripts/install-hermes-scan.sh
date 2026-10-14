#!/usr/bin/env bash
set -euo pipefail
if ! command -v hermes >/dev/null 2>&1; then
  echo "Hermes CLI not installed or not in PATH" >&2
  exit 1
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$HOME/.hermes/scripts"
TARGET="$HOME/.hermes/scripts/ai-trading-mentor-java-scan.sh"
# Generate a script with an explicit path to the app's script.
printf '#!/usr/bin/env bash\nset -euo pipefail\nexec %q\n' "$ROOT/scripts/hermes-scan.sh" > "$TARGET"
chmod 700 "$TARGET"
echo "Installed: $TARGET"
echo "Check 'hermes cron list' for any earlier Python trading mentor job first."
echo "After verifying local API + Telegram gateway, create exactly ONE Hermes scheduled job:"
echo "hermes cron create 'every 15m' --no-agent --script ai-trading-mentor-java-scan.sh --deliver telegram --name 'AI Trading Mentor Java Paper Watch'"
