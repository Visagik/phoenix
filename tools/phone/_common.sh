#!/bin/bash
# Общее для скриптов телефона. Только тестовый телефон, только пакет com.phoenix.app.
PKG=com.phoenix.app
OUT=/workspace/.phone
mkdir -p "$OUT"
adb get-state >/dev/null 2>&1 || { echo "Телефон не подключён (adb get-state)"; exit 1; }
mask() { sed -E 's#(sub/)[A-Za-z0-9_-]+#\1KEY#g; s#(token=|key=|password=)[^&[:space:]]+#\1KEY#g'; }
