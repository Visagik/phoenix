#!/usr/bin/env python3
"""Собирает app/src/main/assets/i18n/<язык>.tsv из tools/i18n/src/<язык>.tsv (ключ ⇥ перевод; \\n — перенос).
Проверяет, что число подстановок {} в переводе совпадает с ключом; пробелы по краям берутся из ключа.
Ключи, у которых нет перевода, перечисляются: в приложении для них показывается русский."""
import os, re, sys, glob
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
d = os.path.join(root, 'app', 'src', 'main', 'assets', 'i18n')
keys = [l.rstrip('\n') for l in open(os.path.join(d, 'keys.txt'), encoding='utf-8') if l.strip('\n')]
def un(s): return s.replace('\\n', '\n').replace('\\\\', '\\')
def esc(s): return s.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', ' ')
ph = re.compile(r'\{(\d*)\}')
bad = 0; incomplete = 0
for f in sorted(glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'src', '*.tsv'))):
    lang = os.path.basename(f)[:-4]
    src = {}
    for line in open(f, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line.strip() or '\t' not in line: continue
        k, _, t = line.partition('\t'); src[k] = t
    out = []; missing = []
    for k in keys:
        if k not in src: missing.append(k); continue
        key = un(k); t = un(src[k])
        lead = key[:len(key) - len(key.lstrip())]; trail = key[len(key.rstrip()):]
        t = lead + t.strip() + trail
        if len(ph.findall(t)) != len(ph.findall(key)):
            print(f'{lang}: подстановки не совпадают: {k[:50]}'); bad += 1; continue
        out.append(k + '\t' + esc(t))
    open(os.path.join(d, lang + '.tsv'), 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print(f'{lang}: {len(out)} из {len(keys)}')
    for k in missing: print(f'  нет перевода [{lang}]: {k[:60]}'); incomplete += 1
sys.exit(1 if (bad or incomplete) else 0)
