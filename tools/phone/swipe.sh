#!/bin/bash
# swipe.sh down|up [N] — прокрутка содержимого: down = вниз по списку (видно то, что ниже), up = обратно вверх. N раз, по умолчанию 1, максимум 5.
. "$(dirname "$0")/_common.sh"
d="$1"; n="${2:-1}"
[[ "$d" =~ ^(up|down)$ && "$n" =~ ^[1-5]$ ]] || { echo "usage: swipe.sh up|down [1-5]"; exit 2; }
read -r W H < <(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
[ -n "$W" ] && [ -n "$H" ] || { echo "не удалось узнать размер экрана"; exit 1; }
x=$((W / 2)); top=$((H * 3 / 10)); bot=$((H * 7 / 10))
for _ in $(seq "$n"); do
  # down: палец движется снизу вверх, содержимое едет вверх, и видно то, что ниже
  if [ "$d" = down ]; then adb shell input swipe "$x" "$bot" "$x" "$top" 350; else adb shell input swipe "$x" "$top" "$x" "$bot" 350; fi
  sleep 0.4
done
