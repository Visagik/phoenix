#!/usr/bin/env python3
"""Как extract.py, но только для строк, подходящих под шаблон (уведомления, тосты, статус VPN).
Запуск: python3 tools/i18n/lines.py '<regex>' файл.kt…"""
import re, sys, os, importlib.util
here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('extract', os.path.join(here, 'extract.py'))
ex = importlib.util.module_from_spec(spec); spec.loader.exec_module(ex)
pat = re.compile(sys.argv[1])
keys = set()
if os.path.exists(ex.KEYS):
    keys |= {ex.__dict__['decode'](l.rstrip('\n').replace('\\n', '\n').replace('\\\\', '\\')) if False else l.rstrip('\n').replace('\\n', '\n').replace('\\\\', '\\') for l in open(ex.KEYS, encoding='utf-8') if l.strip()}
total = 0
for f in sys.argv[2:]:
    lines = open(f, encoding='utf-8').read().split('\n')
    out = []; n = 0
    for ln in lines:
        if pat.search(ln) and not ln.lstrip().startswith('//'):
            try:
                new, ch, sk = ex.process(ln, keys)
            except ValueError:
                new, ch = ln, 0
            if ch: n += ch
            out.append(new)
        else:
            out.append(ln)
    open(f, 'w', encoding='utf-8').write('\n'.join(out))
    print(os.path.basename(f), 'обёрнуто', n); total += n
with open(ex.KEYS, 'w', encoding='utf-8') as o:
    for k in sorted(keys):
        o.write(k.replace('\\', '\\\\').replace('\n', '\\n') + '\n')
print('ключей:', len(keys))
