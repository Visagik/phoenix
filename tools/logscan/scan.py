#!/usr/bin/env python3
"""Ищет в журнале Phoenix места, где логика сработала плохо. Запуск: python3 tools/logscan/scan.py журнал.txt [второй.txt ...] > отчёт.md
Ничего не отправляет и не меняет; адреса подписок в вывод не попадают (режутся sed-подобной маской)."""
import re, sys, collections
from datetime import datetime

TS = re.compile(r'^(\d\d-\d\d) (\d\d:\d\d:\d\d)\s+(.*)$')
MASK = re.compile(r'(your-durev\.com/sub/)[A-Za-z0-9_…-]+')

def parse(path):
    rows = []
    for line in open(path, encoding='utf-8', errors='replace'):
        m = TS.match(line.rstrip('\n'))
        if not m:
            continue
        t = datetime.strptime('2026-' + m.group(1) + ' ' + m.group(2), '%Y-%m-%d %H:%M:%S')
        rows.append((t, MASK.sub(r'\1KEY', m.group(3))))
    return rows

def srv(s):
    m = re.search(r'\[([^\]]*(?:\[[^\]]*\][^\]]*)*)\]', s)
    return (m.group(1) if m else '?')[:60]

def scan(path):
    rows = parse(path)
    out = []
    add = out.append
    env = next((r for _, r in rows if r.startswith('ENV:')), '')
    add(f'## {path.split("/")[-1]}')
    add(f'`{env[:150]}`  строк: {len(rows)}, {rows[0][0]:%d.%m %H:%M} – {rows[-1][0]:%d.%m %H:%M}\n')

    # 1. долгие простои
    add('### 1. Простои дольше минуты')
    n = 0
    for t, r in rows:
        m = re.match(r'DOWNTIME: связи не было (\d+) с \(([^)]*)\)', r)
        if m and int(m.group(1)) >= 60:
            n += 1; add(f'- {t:%H:%M:%S} — {m.group(1)} с ({m.group(2)})')
    if not n: add('- нет')

    # 2. «жив по поиску, но в туннеле мёртв»
    add('\n### 2. Поиск сказал «жив», а через туннель связи нет')
    cnt = collections.Counter()
    for t, r in rows:
        if 'ответил поиску' in r and 'но через туннель связи нет' in r:
            cnt[srv(r)] += 1
    for s, c in cnt.most_common(8): add(f'- ×{c}  {s}')
    add(f'- всего: {sum(cnt.values())}')

    # 3. сервер упал в первые 90 с после подключения
    add('\n### 3. Сервер упал вскоре после выбора (< 90 с)')
    last_conn = {}
    n = 0
    for t, r in rows:
        m = re.match(r'VPN: (?:подключено|переключился на запасной|из-за низкой скорости переключился на) \[([^\]]*)\]', r)
        if m: last_conn[m.group(1)[:60]] = t
        m = re.match(r'HEALTH: нет связи через \[([^\]]*)\], неудача (\d+)', r)
        if m and int(m.group(2)) >= 2:
            s = m.group(1)[:60]
            if s in last_conn and 0 <= (t - last_conn[s]).total_seconds() < 90:
                n += 1; add(f'- {t:%H:%M:%S} {s}: через {(t - last_conn[s]).total_seconds():.0f} с после подключения')
    if not n: add('- нет')

    # 4. ложные тревоги здоровья
    fails = [(t, r) for t, r in rows if r.startswith('HEALTH: нет связи')]
    rec = [(t, r) for t, r in rows if 'восстановилась' in r and r.startswith('HEALTH')]
    add('\n### 4. Проверка здоровья')
    add(f'- падений: {len(fails)}, из них сама восстановилась: {len(rec)} ({100*len(rec)//max(1,len(fails))}% ложных или кратких)')
    per = collections.Counter(srv(r.split('через',1)[1]) for _, r in fails if 'через' in r)
    for s, c in per.most_common(5): add(f'- ×{c}  {s}')

    # 5. смена сервера сразу после смены сети
    add('\n### 5. Смена сервера в течение 60 с после смены сети')
    net = [t for t, r in rows if r.startswith('NET: сеть сменилась:')]
    n = 0
    for t, r in rows:
        if r.startswith('FAILOVER:') and 'не работает' in r:
            if any(0 <= (t - x).total_seconds() < 60 for x in net):
                n += 1; add(f'- {t:%H:%M:%S} {r[:110]}')
    if not n: add('- нет')
    add(f'- всего смен сети: {len(net)}')

    # 6. «умная смена» подряд
    add('\n### 6. Умная смена чаще раза в 2 минуты')
    sm = [t for t, r in rows if r.startswith('SMART: умная смена')]
    burst = [(a, b) for a, b in zip(sm, sm[1:]) if (b - a).total_seconds() < 120]
    add(f'- пар подряд: {len(burst)}' + (f' (первая {burst[0][0]:%H:%M:%S})' if burst else ''))

    # 7. долгий поиск сервера и предпроверка
    add('\n### 7. Медленные поиски')
    for t, r in rows:
        m = re.search(r'поиск (\d+) с', r)
        if m and int(m.group(1)) >= 60 and 'переключился' in r:
            add(f'- {t:%H:%M:%S} поиск {m.group(1)} с — {r[:100]}')
        m = re.match(r'MODE: предпроверка параллельно за (\d+) с — ответили (\d+) из (\d+)', r)
        if m and int(m.group(1)) >= 20:
            add(f'- {t:%H:%M:%S} предпроверка режима {m.group(1)} с, ответили {m.group(2)} из {m.group(3)}')

    # 8. прерванные перепроверки
    ab = [(t, r) for t, r in rows if r.startswith('RECHECK: прервана')]
    add('\n### 8. Прерванные перепроверки')
    for t, r in ab: add(f'- {t:%H:%M:%S} {r[:110]}')
    if not ab: add('- нет')

    # 9. ложные «нет сети» в обходе
    nb = sum(1 for _, r in rows if 'напрямую не открылось ничего' in r)
    add(f'\n### 9. «Напрямую не открылось ничего — похоже, сети нет»: {nb} раз')
    if nb > 5: add('- если это при рабочем VPN, проверка обхода запускается слишком часто (трафик и батарея)')

    # 10. TG-DC
    tg = [r for _, r in rows if r.startswith('TG-DC:')]
    if tg:
        same = sum('дата-центр отвечает (не 200/204)' in r for r in tg)
        add(f'\n### 10. TG-DC: {len(tg)} строк, из них «отвечает (не 200/204)»: {same} — {"проба ни разу не отличилась" if same == len(tg) else "есть различия"}')
    return '\n'.join(out)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print('# Отчёт по журналам Phoenix\n')
    for p in sys.argv[1:]:
        print(scan(p)); print()
