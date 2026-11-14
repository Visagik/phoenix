#!/usr/bin/env python3
"""Добавляет новые ключи и переводы (порядок языков: en es tr uk zh ar fa); уже существующие ключи пропускает."""
import sys, json
LANGS = ['en', 'es', 'tr', 'uk', 'zh', 'ar', 'fa']
rows = json.load(open(sys.argv[1], encoding='utf-8'))
keys = open('app/src/main/assets/i18n/keys.txt', encoding='utf-8').read().split('\n')
have = set(keys)
added = 0
for r in rows:
    ru, tl = r[0], r[1:]
    assert len(tl) == 7, ru
    if ru in have:
        continue
    added += 1
    with open('app/src/main/assets/i18n/keys.txt', 'a', encoding='utf-8') as f:
        f.write(ru + '\n')
    for l, t in zip(LANGS, tl):
        with open(f'tools/i18n/src/{l}.tsv', 'a', encoding='utf-8') as f:
            f.write(ru + '\t' + t + '\n')
print('добавлено', added)
