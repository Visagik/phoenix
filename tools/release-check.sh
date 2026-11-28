#!/usr/bin/env bash
# Автоматическая часть списка перед выпуском (docs/RELEASE_CHECKLIST.md). Без сети.
# Запуск:  bash tools/release-check.sh [--final]
# --final: ещё строка «Подпись:» в заметках (64 hex, двоеточия допустимы) и состав репозитория (git ls-files).
set -u
cd "$(dirname "$0")/.."
FINAL=0
[ "${1:-}" = "--final" ] && FINAL=1
GRADLE=app/build.gradle.kts
fail=0
ok() { echo "ок: $1"; }
bad() { echo "ОШИБКА: $1"; fail=1; }

V=$(grep -oP 'versionName = "\K[^"]+' "$GRADLE" | head -1)
C=$(grep -oP 'versionCode = .*\?: \K[0-9]+' "$GRADLE" | head -1)
if [ -z "$V" ] || [ -z "$C" ]; then
  echo "ОШИБКА: не нашёл versionName/versionCode в $GRADLE"; exit 1
fi
echo "Версия $V (код $C)"

case "$V" in *-dev*|*dev*) bad "versionName «$V» содержит «dev»" ;; *) ok "versionName без «-dev»" ;; esac

NOTES="RELEASE_NOTES_${V}.md"
[ -f "$NOTES" ] && ok "есть $NOTES" || bad "нет $NOTES"

if grep -q "^## Версия ${V}\b" CHANGELOG.md; then
  ok "в CHANGELOG есть «## Версия $V»"
else
  bad "в CHANGELOG нет «## Версия $V»"
fi
# в разделе «В работе» (до следующего «## ») не должно быть непустых строк
pending=$(awk '/^## В работе/{f=1;next} /^## /{f=0} f && NF' CHANGELOG.md | wc -l)
[ "$pending" -eq 0 ] && ok "«В работе» в CHANGELOG пустой" || bad "в «В работе» CHANGELOG ещё $pending непустых строк — перенести в «## Версия $V»"

grep -q "Entry(\"${V}\"" app/src/main/java/com/phoenix/app/WhatsNew.kt && ok "WhatsNew: есть Entry(\"$V\")" || bad "WhatsNew: нет Entry(\"$V\")"

RF=$(grep -oP 'REVIEWED_FOR = "\K[^"]+' app/src/main/java/com/phoenix/app/Facts.kt | head -1)
[ "$RF" = "$V" ] && ok "Facts.REVIEWED_FOR = \"$V\"" || bad "Facts.REVIEWED_FOR = \"${RF:-?}\", а версия $V — факты бегущей строки не пересмотрены"

if python3 -I tools/i18n/check.py >/tmp/release-check-i18n.txt 2>&1; then
  ok "i18n без пропусков"
else
  bad "tools/i18n/check.py: $(tail -1 /tmp/release-check-i18n.txt)"
fi
if bash tools/check-docs.sh >/tmp/release-check-docs.txt 2>&1; then
  ok "check-docs"
else
  bad "tools/check-docs.sh: $(tail -1 /tmp/release-check-docs.txt)"
fi

if [ "$FINAL" = 1 ]; then
  sig=0
  if [ -f "$NOTES" ]; then
    # та же логика, что UpdateCheck.signingFromNotes: строка со словом «подпись», двоеточия между парами hex убираются, ищется 64 hex
    while IFS= read -r line; do
      case "$line" in *Подпись*|*подпись*|*ПОДПИСЬ*|*Signing*|*signing*|*Signature*|*signature*|*SIGNING*|*SIGNATURE*) ;; *) continue ;; esac
      hex=$(echo "$line" | sed -E ':a;s/([0-9a-fA-F]{2}):([0-9a-fA-F]{2})/\1\2/;ta')
      if echo "$hex" | grep -qE '(^|[^0-9a-zA-Z])[0-9a-fA-F]{64}($|[^0-9a-zA-Z])'; then sig=1; break; fi
    done < "$NOTES"
  fi
  [ "$sig" = 1 ] && ok "в заметках строка «Подпись:» с 64 hex" || bad "в $NOTES нет строки «Подпись: <SHA-256, 64 hex>»"

  if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
    bad_files=$(git ls-files | grep -E '\.apk$|\.jks$|\.keystore$|^app/src/lite/jniLibs/' || true)
    [ -z "$bad_files" ] && ok "git: нет APK, ключей и .so из Lite" || bad "в git лежит: $(echo "$bad_files" | tr '\n' ' ')"
  else
    echo "пропущено: git недоступен (проверить git ls-files вручную)"
  fi
fi

echo
echo "Ручные пункты — docs/RELEASE_CHECKLIST.md."
exit $fail
