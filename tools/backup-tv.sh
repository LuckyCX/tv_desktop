#!/usr/bin/env bash
# 完整备份：packages / settings / HOME / 可启动应用
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export PATH="$ROOT/.sdk/platform-tools:${PATH:-}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/backup/$TS"
mkdir -p "$OUT"
ADB=("$ROOT/.sdk/platform-tools/adb" "-s" "${TV_HOST}:5555")

echo "Connecting ${TV_HOST}:5555 ..."
"${ADB[@]}" connect "${TV_HOST}:5555" >/dev/null || true
STATE="$("${ADB[@]}" get-state 2>/dev/null | tr -d '\r' || true)"
if [[ "$STATE" != "device" ]]; then
  echo "ADB not authorized (state=$STATE). Accept USB debugging on the TV, then re-run." >&2
  exit 1
fi

echo "Backing up to $OUT"
"${ADB[@]}" shell pm list packages >"$OUT/packages.txt"
"${ADB[@]}" shell pm list packages -d >"$OUT/packages-d.txt"
"${ADB[@]}" shell pm list packages -e >"$OUT/packages-e.txt"
"${ADB[@]}" shell getprop >"$OUT/getprop.txt"
"${ADB[@]}" shell settings list system >"$OUT/settings-system.txt" || true
"${ADB[@]}" shell settings list secure >"$OUT/settings-secure.txt" || true
"${ADB[@]}" shell settings list global >"$OUT/settings-global.txt" || true
"${ADB[@]}" shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME \
  >"$OUT/home-activities.txt" || true
# 可启动应用（Leanback / launcher）
"${ADB[@]}" shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER \
  >"$OUT/leanback-apps.txt" || true
"${ADB[@]}" shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  >"$OUT/launcher-apps.txt" || true

cat >"$OUT/RESTORE.md" <<EOF
# Vidda 备份恢复说明 ($TS)

## 恢复被 disable 的包
\`\`\`bash
TV_HOST=$TV_HOST $ROOT/tools/restore.sh $OUT
\`\`\`

## 切回原桌面
\`\`\`bash
$ROOT/switch-stock.sh
# 或: adb -s ${TV_HOST}:5555 shell pm enable --user 0 com.jamdeo.tv.vod
\`\`\`

## 切回 CleanYourTV
\`\`\`bash
$ROOT/switch-clean.sh
\`\`\`
EOF

echo "Backup complete: $OUT"
ls -la "$OUT"
