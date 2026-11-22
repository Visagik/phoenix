#!/bin/bash
# tap.sh X Y — нажатие; tap.sh back — кнопка «Назад»; tap.sh home — домой.
. "$(dirname "$0")/_common.sh"
case "$1" in
  back) adb shell input keyevent KEYCODE_BACK ;;
  home) adb shell input keyevent KEYCODE_HOME ;;
  *) [[ "$1" =~ ^[0-9]+$ && "$2" =~ ^[0-9]+$ ]] || { echo "usage: tap.sh X Y | back | home"; exit 2; }
     adb shell input tap "$1" "$2" ;;
esac
