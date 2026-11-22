#!/bin/bash
# app.sh start | stop | state — только com.phoenix.app.
. "$(dirname "$0")/_common.sh"
case "$1" in
  start) adb shell am start -n "$PKG/.MainActivity" ;;
  stop) adb shell am force-stop "$PKG" ;;
  state) adb shell pidof "$PKG" || echo "не запущен"; adb shell dumpsys package "$PKG" | grep -m3 -E "versionName|versionCode" ;;
  *) echo "usage: app.sh start|stop|state"; exit 2 ;;
esac
