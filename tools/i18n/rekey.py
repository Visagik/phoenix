#!/usr/bin/env python3
"""Меняет русский ключ строки интерфейса и правит её переводы: rekey.py <файл-JSON с {old, new, patches:{lang:[[from,to],...]}}>.
Нужен, когда правится сама русская фраза: ключом служит русский текст, поэтому переводы надо перенести на новый ключ."""
import json, sys, io
cfg = json.load(open(sys.argv[1], encoding='utf-8'))
old, new = cfg['old'], cfg['new']
def rw(path, fn):
    s = open(path, encoding='utf-8').read(); t = fn(s); open(path, 'w', encoding='utf-8').write(t); return s != t
rw('app/src/main/assets/i18n/keys.txt', lambda s: s.replace(old + '\n', new + '\n'))
for lang in ['en','es','tr','uk','zh','ar','fa']:
    path = f'tools/i18n/src/{lang}.tsv'
    out = []
    hit = False
    for line in open(path, encoding='utf-8').read().split('\n'):
        if line.startswith(old + '\t'):
            hit = True
            tr = line.split('\t', 1)[1]
            for a, b in cfg['patches'].get(lang, []):
                assert a in tr, (lang, a)
                tr = tr.replace(a, b, 1)
            line = new + '\t' + tr
        out.append(line)
    assert hit, lang
    open(path, 'w', encoding='utf-8').write('\n'.join(out))
