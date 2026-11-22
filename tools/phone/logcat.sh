#!/bin/bash
# Короткий logcat приложения: logcat.sh [секунды, по умолчанию 20]. Только процесс com.phoenix.app, ключи маскируются.
. "$(dirname "$0")/_common.sh"
s="${1:-20}"; [[ "$s" =~ ^[0-9]+$ ]] || s=20
pid=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$pid" ] || { echo "приложение не запущено"; exit 1; }
timeout "$s" adb logcat --pid="$pid" -v time 2>&1 | mask | tail -n 300
