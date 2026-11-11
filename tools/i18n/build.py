#!/usr/bin/env python3
"""Собирает app/src/main/assets/i18n/<язык>.tsv из tools/i18n/src/<язык>.*.tsv (номер строки keys.txt ⇥ перевод).
Проверяет, что число подстановок {} в переводе совпадает с ключом. Пробелы по краям берутся из ключа."""
import os, re, sys, glob
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
keys_path = os.path.join(root, 'app', 'src', 'main', 'assets', 'i18n', 'keys.txt')
keys = [l.rstrip('\n') for l in open(keys_path, encoding='utf-8')]
def un(s): return s.replace('\\n', '\n').replace('\\\\', '\\')
def esc(s): return s.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', ' ')
ph = re.compile(r'\{(\d*)\}')
def count(s): return len(ph.findall(s))
bad = 0
for lang in sorted({os.path.basename(f).split('.')[0] for f in glob.glob(os.path.join(os.path.dirname(__file__), 'src', '*.tsv'))}):
    tr = {}
    for f in sorted(glob.glob(os.path.join(os.path.dirname(__file__), 'src', lang + '.*.tsv'))):
        for line in open(f, encoding='utf-8'):
            line = line.rstrip('\n')
            if not line.strip(): continue
            n, _, t = line.partition('\t')
            tr[int(n)] = t
    out = []
    missing = []
    for i, k in enumerate(keys, 1):
        if i not in tr: missing.append(i); continue
        key = un(k); t = un(tr[i])
        lead = key[:len(key) - len(key.lstrip())]; trail = key[len(key.rstrip()):]
        t = lead + t.strip() + trail
        if count(t) != count(key):
            print(f'{lang}:{i}: подстановок {count(t)} вместо {count(key)}: {k[:50]}'); bad += 1; continue
        out.append(k + '\t' + esc(t))
    d = os.path.join(root, 'app', 'src', 'main', 'assets', 'i18n')
    open(os.path.join(d, lang + '.tsv'), 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print(f'{lang}: {len(out)} из {len(keys)}' + (f', нет {len(missing)}' if missing else ''))
sys.exit(1 if bad else 0)
