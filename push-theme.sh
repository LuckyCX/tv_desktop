#!/usr/bin/env bash
# 局域网推送主题（无需重编）：./push-theme.sh themes/aurora
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
THEME="${1:-themes/aurora}"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export PATH="$ROOT/.sdk/platform-tools:$PATH"
exec ./tools/theme.sh "$THEME"
