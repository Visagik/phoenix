#!/bin/bash
# Что сейчас на экране: тексты и координаты элементов (uiautomator). Для точных нажатий.
. "$(dirname "$0")/_common.sh"
adb shell uiautomator dump /sdcard/phoenix-ui.xml >/dev/null 2>&1
adb exec-out cat /sdcard/phoenix-ui.xml | python3 -I -c '
import sys,re,html
x=html.unescape(sys.stdin.read())
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    t=re.search(r"text=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    c=re.search(r"clickable=\"(\w+)\"",n)
    s=(t.group(1) if t else "") or (d.group(1) if d else "")
    if s and b:
        a=[int(v) for v in b.groups()]
        print("%s  (%d,%d)%s"%(s[:80],(a[0]+a[2])//2,(a[1]+a[3])//2," [кнопка]" if c and c.group(1)=="true" else ""))
'
adb shell rm -f /sdcard/phoenix-ui.xml
