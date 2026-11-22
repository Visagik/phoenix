#!/bin/bash
# open.sh https://домен/путь — открыть ссылку в Chrome на ТЕСТОВОМ телефоне (проверка, что Феникс пропускает трафик).
# Только https и только домены из open-allow.txt. Дальше: shot.sh. Вернуться в Феникс: app.sh start.
. "$(dirname "$0")/_common.sh"
url="$1"
[[ "$url" =~ ^https://[A-Za-z0-9._~:/?#!$\&\(\)*+,\;=%@-]+$ ]] || { echo "ссылка не принята (нужен https и обычные символы, без кавычек и пробелов)"; exit 2; }
[[ "$url" == *"@"* ]] && { echo "ссылка с @ не принимается"; exit 2; }
host="${url#https://}"; host="${host%%[/?#]*}"; host="${host%%:*}"; host="${host,,}"
[ -n "$host" ] || { echo "нет домена"; exit 2; }
ok=0
while IFS= read -r d; do
  d="${d%%#*}"; d="${d//[[:space:]]/}"; [ -z "$d" ] && continue
  if [ "$host" = "$d" ] || [[ "$host" == *".$d" ]]; then ok=1; break; fi
done < "$(dirname "$0")/open-allow.txt"
[ "$ok" = 1 ] || { echo "домен $host не в open-allow.txt"; exit 3; }
# ссылка в одинарных кавычках: символы & ; ( ) не разбираются оболочкой телефона (одинарная кавычка во вводе запрещена проверкой выше)
adb shell "am start -a android.intent.action.VIEW -d '$url' -p com.android.chrome" 2>&1 | grep -v "^Starting" || true
echo "открыто: $host (подожди 5–10 с и сделай shot.sh)"
