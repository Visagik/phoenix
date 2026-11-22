#!/bin/bash
# net.sh wifi on|off | data on|off — переключение сети на ТЕСТОВОМ телефоне (проверка смены сети и режима).
. "$(dirname "$0")/_common.sh"
case "$1 $2" in
  "wifi on"|"wifi off"|"data on"|"data off")
    v=$([ "$2" = on ] && echo enable || echo disable)
    adb shell svc "$1" "$v" ;;
  *) echo "usage: net.sh wifi|data on|off"; exit 2 ;;
esac
