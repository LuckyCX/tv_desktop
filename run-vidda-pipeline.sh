#!/usr/bin/env bash
# Vidda 一键：备份 → 净化 → 安装桌面（需 ADB 已授权）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$ROOT/.sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ROOT/.sdk/platform-tools:$PATH"
export HOME_KEY_SERVICE="${HOME_KEY_SERVICE:-1}"
export STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.jamdeo.tv.vod}"
ADB=("$ROOT/.sdk/platform-tools/adb" "-s" "${TV_HOST}:5555")
PY="$ROOT/tools/venv/bin/python"

echo "== connect =="
"${ADB[@]}" connect "${TV_HOST}:5555" || true
line="$("${ADB[@]}" devices 2>/dev/null | grep "${TV_HOST}:5555" || true)"
echo "$line"
echo "$line" | grep -qE $'\tdevice$' || { echo "ADB not authorized"; exit 1; }

echo "== probe =="
for p in ro.product.model ro.product.device ro.product.board ro.build.version.release ro.adb.secure; do
  echo -n "$p="; "${ADB[@]}" shell getprop "$p" | tr -d '\r'
done
mkdir -p "$ROOT/backup"
PROBE_DIR="$ROOT/backup/probe-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$PROBE_DIR"
"${ADB[@]}" shell pm list packages >"$PROBE_DIR/packages.txt"
"${ADB[@]}" shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER \
  >"$PROBE_DIR/leanback.txt" || true
echo "probe saved: $PROBE_DIR"

echo "== backup =="
"$ROOT/tools/backup-tv.sh"

echo "== debloat =="
TV_HOST="$TV_HOST" "$ROOT/tools/disable.sh" "$ROOT/examples/vidda-local/debloat.txt"

echo "== launcher =="
# Prefer prebuilt APK if present
APK="$ROOT/launcher/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  (cd "$ROOT/launcher" && echo "sdk.dir=$ANDROID_HOME" > local.properties && ./gradlew assembleRelease -q --console=plain)
fi
TV_HOST="$TV_HOST" HOME_KEY_SERVICE=1 STOCK_LAUNCHER=com.jamdeo.tv.vod \
  "$ROOT/launcher/build-and-install.sh"

echo "== verify home =="
"${ADB[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r'
echo "Pipeline done. Reminder: close USB debugging on TV, then:"
echo "  $PY -I $ROOT/tools/adb-closed.py $TV_HOST"
