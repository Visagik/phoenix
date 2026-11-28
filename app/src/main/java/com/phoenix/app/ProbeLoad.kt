package com.phoenix.app

import java.io.File

/**
 * Параллельность проверок в зависимости от занятой памяти процесса. Журнал Samsung 21.11: при перепроверке вместе с поиском PSS доходил до 332 МБ,
 * а в 12:22 процесс убила система («не хватило памяти», 243 МБ). Каждая проба временным ядром — отдельный экземпляр Go.
 */
object MemGuard {
    // в Lite пороги ниже (LiteProfile)
    val HIGH_MB: Int get() = LiteProfile.memHigh(200)
    val CRITICAL_MB: Int get() = LiteProfile.memCritical(260)

    /** Сколько проверок одновременно можно держать при [pssMb] занятой памяти; [base] — обычное значение. */
    fun scale(base: Int, pssMb: Int): Int = when {
        pssMb <= 0 -> base // не удалось измерить
        pssMb >= CRITICAL_MB -> maxOf(1, base / 4)
        pssMb >= HIGH_MB -> maxOf(2, base / 2).coerceAtMost(base)
        else -> base
    }
}

/**
 * Первый запуск без истории (нет бэкапа, ни одного успешного сбора): всё надо проверять с нуля, десятки тысяч
 * конфигов. Чтобы телефон не захлёбывался (приложение «не отвечает»), первый сбор откладывается на минуту-полторы,
 * а первая перепроверка идёт втрое меньшим числом параллельных проверок.
 */
object ColdStart {
    const val FIRST_DELAY_SEC = 90L

    fun isFirstRun(lastSuccessAt: Long): Boolean = lastSuccessAt <= 0L

    /** Сколько проверок через ядро одновременно при перепроверке; [base] — обычное число для телефона. */
    fun recheckParallel(base: Int, lastSuccessAt: Long): Int =
        if (isFirstRun(lastSuccessAt)) (base / 3).coerceAtLeast(2) else base

    /** Задержка запуска «догона» при старте процесса; ручной запуск и будильник не откладываются. */
    fun catchUpDelaySec(trigger: String, lastSuccessAt: Long): Long =
        if (trigger == "catch-up" && isFirstRun(lastSuccessAt)) FIRST_DELAY_SEC else 0L
}
