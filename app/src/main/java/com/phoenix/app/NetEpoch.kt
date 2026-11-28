package com.phoenix.app

import java.util.concurrent.atomic.AtomicLong

/**
 * Эпоха сети: номер растёт при каждой смене сети. Факт о сети («интернета нет», «определён тип блокировок») действует только в той эпохе,
 * где измерен. Раньше это были глобальные числа без метки сети: «нет интернета» от Wi-Fi ещё до двух минут блокировало перебор серверов
 * уже на мобильной сети, а тип блокировок от прежней сети откладывал перебор в новой.
 */
object NetEpoch {
    private val n = AtomicLong(1)
    val id: Long get() = n.get()
    fun bump(): Long = n.incrementAndGet()
}

/** Значение, которое видно только в эпохе записи; в чужой эпохе читается как [empty]. */
class EpochFact<T>(private val empty: T, private val epoch: () -> Long = { NetEpoch.id }) {
    @Volatile private var v: T = empty
    @Volatile private var at = 0L
    var value: T
        get() = if (at == epoch()) v else empty
        set(x) { v = x; at = epoch() }
}
