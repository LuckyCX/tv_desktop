#!/usr/bin/env bash
# 从 backup/<timestamp>/ 恢复：重新 enable 曾被 disable 的包，并切回原桌面。
# 用法: TV_HOST=192.168.0.110 ./tools/restore-from-backup.sh [backup/<timestamp>]
# 日常净化回滚请用: tools/restore.sh examples/vidda-local/debloat.txt com.jamdeo.tv.vod
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export PATH="$ROOT/.sdk/platform-tools:${PATH:-}"
ADB=("$ROOT/.sdk/platform-tools/adb" "-s" "${TV_HOST}:5555")

BACKUP_DIR="${1:-}"
if [[ -z "$BACKUP_DIR" ]]; then
  BACKUP_DIR="$(ls -1d "$ROOT"/backup/*/ 2>/dev/null | sort | tail -1 || true)"
fi
if [[ -z "$BACKUP_DIR" || ! -d "$BACKUP_DIR" ]]; then
  echo "No backup dir found. Pass backup/<timestamp> explicitly." >&2
  exit 1
fi
BACKUP_DIR="${BACKUP_DIR%/}"
echo "Restoring from: $BACKUP_DIR"
"${ADB[@]}" connect "${TV_HOST}:5555" >/dev/null || true

if [[ -f "$BACKUP_DIR/packages-e.txt" ]]; then
  CUR_DIS="$("${ADB[@]}" shell pm list packages -d 2>/dev/null | tr -d '\r' | sed 's/^package://' | sort -u)"
  BAK_EN="$(sed 's/^package://' "$BACKUP_DIR/packages-e.txt" | sort -u)"
  while IFS= read -r pkg; do
    [[ -z "$pkg" ]] && continue
    if echo "$BAK_EN" | grep -qx "$pkg"; then
      echo "pm enable $pkg"
      "${ADB[@]}" shell pm enable --user 0 "$pkg" || true
    fi
  done <<< "$CUR_DIS"
fi

STOCK="${STOCK_LAUNCHER:-com.jamdeo.tv.vod}"
echo "Enabling stock launcher: $STOCK"
"${ADB[@]}" shell pm enable --user 0 "$STOCK" || true
"${ADB[@]}" shell input keyevent KEYCODE_HOME || true
echo "Restore done. Reboot TV if Home still points to CleanYourTV."
