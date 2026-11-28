#!/usr/bin/env bash
# Сверка ARCHITECTURE.md с кодом.
# 1) каждый файл .kt из таблицы должен существовать (ошибка);
# 2) объекты и классы с именами *Gate, *Guard, *Store, *Decision, *Quarantine* должны быть упомянуты в таблице (предупреждение;
#    с STRICT=1 тоже ошибка). Это защита от второго NetFlap: новое «кто решает» без записи в карте.
set -u
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/phoenix/app
fail=0
for f in $(grep -oE '`[A-Za-z0-9_]+\.kt`' ARCHITECTURE.md | tr -d '`' | sort -u); do
  [ -f "$SRC/$f" ] || { echo "ОШИБКА: $f есть в ARCHITECTURE.md, но файла нет"; fail=1; }
done
warn=0
for n in $(grep -hoE '^(internal )?(object|class) [A-Za-z0-9_]*(Gate|Guard|Store|Decision|Quarantine)[A-Za-z0-9_]*' $SRC/*.kt | awk '{print $NF}' | sort -u); do
  grep -qw "$n" ARCHITECTURE.md || { echo "нет в карте: $n"; warn=1; }
done
[ "$warn" = 1 ] && echo "(предупреждения: добавь строку в ARCHITECTURE.md или реши, что это не «кто решает»)"
[ "$fail" = 0 ] && [ "${STRICT:-0}" = 1 ] && [ "$warn" = 1 ] && fail=1
[ "$fail" = 0 ] && echo "check-docs: ок"
exit $fail
