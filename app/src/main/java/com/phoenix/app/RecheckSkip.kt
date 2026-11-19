package com.phoenix.app

/**
 * Что не нужно перепроверять заново: конфиг недавно отмечен мёртвым (проверка свежее [Targets.DEAD_TTL_DAYS]) и в этой сети уже
 * несколько раз подряд подводил. Из списка он не удаляется: в другой сети или через пару дней проверяется как обычно.
 * Раньше каждая перепроверка заново ждала таймаут у одних и тех же мёртвых серверов (порт открыт, интернета нет): вторая половина шла в 5 раз медленнее.
 */
object RecheckSkip {
    /** Столько проб подряд без единого ответа на одном host:port — остальные конфиги этого адреса не пробуем. */
    const val HOST_STRIKES = 3

    fun quarantined(c: Candidate): Boolean = c.deadMark && c.bad && !c.steady && !c.alive
}

/** Скорость перепроверки (настройка): сколько проверок идёт одновременно и сколько ждать открытия порта. */
object RecheckSpeed {
    const val GENTLE = 0
    const val NORMAL = 1
    const val FAST = 2
    const val DEFAULT = NORMAL

    fun clamp(v: Int): Int = v.coerceIn(GENTLE, FAST)

    /** Проверок через ядро одновременно; [base] — обычное число для телефона (16, на слабом 5). Потолок общего лимита XrayCore — 32. */
    fun probes(level: Int, base: Int): Int = when (clamp(level)) {
        GENTLE -> (base / 2).coerceAtLeast(2)
        FAST -> (base * 7 / 4).coerceAtMost(28)
        else -> base
    }

    /** Потолок параллельных TCP-проверок (сверху ещё действует ограничение по сети). */
    fun tcpCap(level: Int): Int = if (clamp(level) == GENTLE) 12 else 48

    /** Сколько ждать открытия порта, мс. */
    fun reachMs(level: Int): Int = if (clamp(level) == FAST) 1_800 else 2_500
}
