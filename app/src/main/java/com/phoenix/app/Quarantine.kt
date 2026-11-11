package com.phoenix.app

/**
 * Мягкий карантин: сервер, на котором оборвалась сессия, [MINUTES] минут идёт в конец очереди, потом получает второй шанс.
 * Если же среди остальных рабочих не нашлось (поиск пуст), карантин снимается и такие серверы проверяются первыми ([releaseAll]).
 * Хранится в памяти: после перезапуска процесса карантин забывается.
 */
object Quarantine {
    const val MINUTES = 10L
    private val until = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun add(identity: String, now: Long = System.currentTimeMillis()) {
        if (identity.isEmpty()) return
        until[identity] = now + MINUTES * 60_000L
    }

    fun active(identity: String, now: Long = System.currentTimeMillis()): Boolean {
        val t = until[identity] ?: return false
        if (now >= t) { until.remove(identity); return false }
        return true
    }

    fun clear() = until.clear()

    /** Сколько серверов сейчас в карантине. */
    fun count(now: Long = System.currentTimeMillis()): Int = until.entries.count { it.value > now }

    /** Снять карантин со всех: другие серверы не нашлись, значит такие надо проверить первыми. Возвращает, сколько снято. */
    fun releaseAll(now: Long = System.currentTimeMillis()): Int {
        val n = count(now)
        until.clear()
        return n
    }
}

/** Как часто проверять туннель: спокойный и долго стабильный сервер проверяем реже, чтобы не будить радиомодуль. */
object HealthPace {
    // кратно общей сетке 30 с: фоновые таймеры просыпаются вместе, и радиомодуль не будится по отдельности
    const val GRID_MS = 30_000L
    const val BASE_MS = 30_000L
    const val CALM_MS = 60_000L
    const val LONG_CALM_MS = 90_000L
    const val SCREEN_OFF_MS = 120_000L

    /** [calmStreak] — сколько проверок подряд без неудач; [failures] — текущие неудачи. */
    const val HOT_MS = 180_000L

    /** [hot] — телефон греется: пока всё спокойно, проверяем туннель реже. */
    const val LOW_BATTERY_MS = 90_000L
    const val LOW_BATTERY_PCT = 20

    /** Заряд низкий и телефон не на зарядке (или включена экономия): пока спокойно, проверяем не чаще, чем раз в 90 с. */
    fun lowBattery(percent: Int, charging: Boolean, powerSave: Boolean): Boolean = !charging && (powerSave || percent in 0 until LOW_BATTERY_PCT)

    fun nextDelay(screenOn: Boolean, failures: Int, calmStreak: Int, hot: Boolean = false, lowBattery: Boolean = false): Long {
        val d = when {
            failures > 0 -> BASE_MS
            hot -> HOT_MS
            !screenOn -> SCREEN_OFF_MS
            calmStreak >= 24 -> LONG_CALM_MS
            calmStreak >= 8 -> CALM_MS
            else -> BASE_MS
        }
        return if (lowBattery && failures == 0) maxOf(d, LOW_BATTERY_MS) else d
    }
}

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
