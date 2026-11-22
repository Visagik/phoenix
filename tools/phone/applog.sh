#!/bin/bash
# Журнал самого приложения (debug-сборка): applog.sh [N последних строк, по умолчанию 200] [фильтр grep -E].
# Ключи и ссылки подписок маскируются. Папка режима: белые списки — files/, чёрные — files/mode_bl/.
. "$(dirname "$0")/_common.sh"
n="${1:-200}"; [[ "$n" =~ ^[0-9]+$ ]] || n=200
pat="${2:-.}"
for f in files/download.log files/mode_bl/download.log; do
  echo "== $f"
  adb shell "run-as $PKG sh -c 'tail -n $n $f 2>/dev/null'" | grep -E -- "$pat" | mask
done
