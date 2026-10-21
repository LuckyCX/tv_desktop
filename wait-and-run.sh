#!/usr/bin/env bash
# 轮询 ADB 授权；一旦 device 状态则跑完整 Vidda 流水线
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export PATH="$ROOT/.sdk/platform-tools:$PATH"
ADB="$ROOT/.sdk/platform-tools/adb"
HOST=192.168.0.110
LOG=/tmp/wait-and-run.log
echo "wait-and-run start $(date)" | tee -a "$LOG"
for i in $(seq 1 360); do
  "$ADB" connect "${HOST}:5555" >/dev/null 2>&1 || true
  line=$("$ADB" devices 2>/dev/null | grep "${HOST}:5555" || true)
  echo "$(date +%H:%M:%S) #$i $line" | tee -a "$LOG"
  if echo "$line" | grep -q $'\tdevice$'; then
    echo "AUTHORIZED — running pipeline" | tee -a "$LOG"
    exec "$ROOT/run-vidda-pipeline.sh"
  fi
  sleep 5
done
echo "TIMEOUT waiting for ADB auth" | tee -a "$LOG"
exit 1
