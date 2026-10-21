#!/usr/bin/env bash
# 局域网重新编译并安装 CleanYourTV 桌面
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export TV_HOST="${TV_HOST:-192.168.0.110}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
# 优先用仓库内 .sdk（已装 platform-34 + build-tools）；回退到 Homebrew cmdline-tools
export ANDROID_HOME="${ANDROID_HOME:-$ROOT/.sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ROOT/.sdk/platform-tools:/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin:$PATH"
export HOME_KEY_SERVICE="${HOME_KEY_SERVICE:-1}"
export STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.jamdeo.tv.vod}"
exec ./launcher/build-and-install.sh
