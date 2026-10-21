#!/usr/bin/env bash
# 切回 Vidda 原桌面（启用 com.jamdeo.tv.vod，该机型会抢回 Home）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export PATH="$ROOT/.sdk/platform-tools:$PATH"
ADB=("$ROOT/.sdk/platform-tools/adb" "-s" "${TV_HOST}:5555")
"${ADB[@]}" connect "${TV_HOST}:5555" >/dev/null || true
echo "Enabling stock launcher com.jamdeo.tv.vod ..."
"${ADB[@]}" shell pm enable --user 0 com.jamdeo.tv.vod
# 尝试回到 Home
"${ADB[@]}" shell input keyevent KEYCODE_HOME || true
echo "Done. Stock JuUI home should reclaim. If not, reboot the TV."
