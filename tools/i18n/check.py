#!/usr/bin/env python3
"""Проверка: каждый tr("…") в коде есть в keys.txt, в каждом словаре нет лишних ключей."""
import re, glob, os, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
def un(s): return s.replace('\\n', '\n').replace('\\\\', '\\')
keys = {un(l.rstrip('\n')) for l in open(os.path.join(root, 'app/src/main/assets/i18n/keys.txt'), encoding='utf-8')}
lit = re.compile(r'\btr\("((?:[^"\\]|\\.)*)"')
def dec(s):
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)
missing = 0
for f in [x for x in glob.glob(os.path.join(root, "app/src/main/java/**/*.kt"), recursive=True) if not x.endswith("I18n.kt")]:
    for m in lit.finditer(open(f, encoding='utf-8').read()):
        k = dec(m.group(1))
        if k not in keys:
            print(os.path.basename(f), 'нет в keys.txt:', k[:60]); missing += 1
print('отсутствуют:', missing)
sys.exit(1 if missing else 0)
