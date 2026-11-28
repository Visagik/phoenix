package com.phoenix.app

/**
 * Тяжёлые фоновые задачи (полная перепроверка, аудит источников) по умолчанию идут на зарядке.
 * Чтобы они не голодали, если телефон давно не заряжали, их разрешают и без зарядки: при хорошем заряде, не на мобильной сети,
 * и только если давно не запускались. Ручной запуск разрешён всегда.
 */
object HeavyGate {
    const val STARVE_HOURS = 24L
    const val MIN_BATTERY = 50

    /** [quiet] — тихие часы (ручные или по часам активности): без зарядки не будим радиомодуль, исключение «давно не делали» не действует. */
    fun allow(manual: Boolean, charging: Boolean, batteryPct: Int, metered: Boolean, hoursSinceLast: Long, quiet: Boolean = false, hot: Boolean = false): Boolean = when {
        manual -> true
        hot -> false // телефон греется: тяжёлое откладываем даже на зарядке
        charging -> true
        quiet -> false
        hoursSinceLast >= STARVE_HOURS && batteryPct >= MIN_BATTERY && !metered -> true
        else -> false
    }
}
