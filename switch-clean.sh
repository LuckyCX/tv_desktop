#!/usr/bin/env bash
# 切回 CleanYourTV：停用原桌面并指定 HOME
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export PATH="$ROOT/.sdk/platform-tools:$PATH"
ADB=("$ROOT/.sdk/platform-tools/adb" "-s" "${TV_HOST}:5555")
"${ADB[@]}" connect "${TV_HOST}:5555" >/dev/null || true
echo "Disabling stock launcher com.jamdeo.tv.vod ..."
"${ADB[@]}" shell pm disable-user --user 0 com.jamdeo.tv.vod
# Resolve CleanYourTV home activity
HOME_ACT="$("${ADB[@]}" shell cmd package resolve-activity --brief android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1 | tr -d '\r')"
if ! echo "$HOME_ACT" | grep -q 'com.gathin.tvhome'; then
  HOME_ACT="com.gathin.tvhome/.MainActivity"
fi
echo "Setting home activity: $HOME_ACT"
"${ADB[@]}" shell cmd package set-home-activity --user 0 "$HOME_ACT" || \
  "${ADB[@]}" shell cmd package set-home-activity "$HOME_ACT" || true
"${ADB[@]}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME || true
echo "Done. Press Home on the remote to confirm CleanYourTV."
