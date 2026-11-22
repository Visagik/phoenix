#!/bin/bash
# Снимок экрана в /workspace/.phone/shot-ЧЧММСС.png
. "$(dirname "$0")/_common.sh"
f="$OUT/shot-$(date +%H%M%S).png"
adb exec-out screencap -p > "$f" && echo "$f"
