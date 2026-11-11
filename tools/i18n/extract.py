#!/usr/bin/env python3
"""Оборачивает русские строковые литералы Kotlin в tr("шаблон {}", арг...) и собирает ключи.
Запуск: python3 tools/i18n/extract.py <файл.kt>... ; ключи дописываются в app/src/main/assets/i18n/keys.txt
Строки с тегами журнала (CHECK: ...), чисто ASCII и внутри комментариев не трогаются."""
import re, sys, os

CYR = re.compile('[А-Яа-яЁё]')
LOGTAG = re.compile(r'^[A-Z_]{3,}:')
KEYS = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'assets', 'i18n', 'keys.txt')

def decode(s):
    out = []; i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$', "'": "'"}.get(n, '\\' + n)); i += 2
        else:
            out.append(c); i += 1
    return ''.join(out)

def parse_string(src, i):
    """src[i] == '"' (не тройная). Возвращает (конец, части) где часть = ('t', текст_источника) или ('e', выражение)."""
    j = i + 1; parts = []; buf = []
    while j < len(src):
        c = src[j]
        if c == '\\':
            buf.append(src[j:j + 2]); j += 2; continue
        if c == '"':
            if buf: parts.append(('t', ''.join(buf)))
            return j + 1, parts
        if c == '$' and j + 1 < len(src):
            n = src[j + 1]
            if n == '{':
                if buf: parts.append(('t', ''.join(buf))); buf = []
                depth = 1; k = j + 2
                while k < len(src) and depth:
                    ch = src[k]
                    if ch == '"':
                        k, _ = parse_string(src, k); continue
                    if ch == '{': depth += 1
                    elif ch == '}': depth -= 1
                    k += 1
                parts.append(('e', src[j + 2:k - 1])); j = k; continue
            m = re.match(r'[A-Za-z_][A-Za-z_0-9]*', src[j + 1:])
            if m:
                if buf: parts.append(('t', ''.join(buf))); buf = []
                parts.append(('e', m.group(0))); j += 1 + len(m.group(0)); continue
        buf.append(c); j += 1
    raise ValueError('незакрытая строка')

def process(src, keys):
    out = []; i = 0; n = len(src); changed = 0; skipped = 0
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            e = src.find('\n', i); e = n if e < 0 else e
            out.append(src[i:e]); i = e; continue
        if src.startswith('/*', i):
            e = src.find('*/', i); e = n if e < 0 else e + 2
            out.append(src[i:e]); i = e; continue
        if src.startswith('"""', i):
            e = src.find('"""', i + 3); e = n if e < 0 else e + 3
            out.append(src[i:e]); i = e; continue
        if c == "'":  # символьный литерал
            m = re.match(r"'(\\.|[^'\\])'", src[i:])
            if m: out.append(m.group(0)); i += len(m.group(0)); continue
        if c == '"':
            # цепочка "a" + "b" склеивается в один литерал
            chain = []; k = i; end = i
            while True:
                try:
                    e2, parts = parse_string(src, k)
                except ValueError:
                    break
                chain.append(parts); end = e2
                m = re.match(r'\s*\+\s*(?=")', src[end:])
                if m and not src.startswith('"""', end + m.end()):
                    k = end + m.end()
                else:
                    break
            if not chain:
                out.append(c); i += 1; continue
            parts = [p for ch in chain for p in ch]
            whole = src[i:end]
            text_src = ''.join(p[1] for p in parts if p[0] == 't')
            exprs = [p[1] for p in parts if p[0] == 'e']
            if not CYR.search(text_src) or LOGTAG.match(decode(text_src)):
                out.append(whole); i = end; continue
            if any(CYR.search(x) for x in exprs):
                out.append(whole); i = end; skipped += 1; continue
            tmpl = ''.join(decode(p[1]) if p[0] == 't' else '{}' for p in parts)
            keys.add(tmpl)
            lit = tmpl.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\t', '\\t').replace('$', '\\$')
            args = ''.join(', ' + x for x in exprs)
            out.append('tr("' + lit + '"' + args + ')'); changed += 1; i = end; continue
        out.append(c); i += 1
    return ''.join(out), changed, skipped

def main():
    keys = set()
    if os.path.exists(KEYS):
        keys |= {l.rstrip('\n') for l in open(KEYS, encoding='utf-8') if l.strip()}
    keys = {k.replace('\\n', '\n').replace('\\\\', '\\') for k in keys}
    for f in sys.argv[1:]:
        src = open(f, encoding='utf-8').read()
        new, ch, sk = process(src, keys)
        open(f, 'w', encoding='utf-8').write(new)
        print(f"{os.path.basename(f)}: обёрнуто {ch}, пропущено со вложенным русским {sk}")
    os.makedirs(os.path.dirname(KEYS), exist_ok=True)
    with open(KEYS, 'w', encoding='utf-8') as o:
        for k in sorted(keys):
            o.write(k.replace('\\', '\\\\').replace('\n', '\\n') + '\n')
    print('ключей:', len(keys))

if __name__ == '__main__':
    main()
