package com.phoenix.app

/** Как часто проверять туннель: спокойный и долго стабильный сервер проверяем реже, чтобы не будить радиомодуль. */
object HealthPace {
    // кратно общей сетке 30 с: фоновые таймеры просыпаются вместе, и радиомодуль не будится по отдельности
    const val GRID_MS = 30_000L
    const val BASE_MS = 30_000L
    const val CALM_MS = 60_000L
    const val LONG_CALM_MS = 90_000L
    const val SCREEN_OFF_MS = 120_000L

    /** Экран выключен, мобильная сеть, связи нет: повтор раз в минуту, а не раз в 30 с (по журналу 15–18.11 больше 2000 строк HEALTH, почти все — неудачи). */
    const val SCREEN_OFF_MOBILE_FAIL_MS = 60_000L

    /** [calmStreak] — сколько проверок подряд без неудач; [failures] — текущие неудачи. */
    const val HOT_MS = 180_000L

    /** [hot] — телефон греется: пока всё спокойно, проверяем туннель реже. */
    const val LOW_BATTERY_MS = 90_000L
    const val LOW_BATTERY_PCT = 20

    /** Заряд низкий и телефон не на зарядке (или включена экономия): пока спокойно, проверяем не чаще, чем раз в 90 с. */
    fun lowBattery(percent: Int, charging: Boolean, powerSave: Boolean): Boolean = !charging && (powerSave || percent in 0 until LOW_BATTERY_PCT)

    fun nextDelay(screenOn: Boolean, failures: Int, calmStreak: Int, hot: Boolean = false, lowBattery: Boolean = false, mobile: Boolean = false): Long {
        val d = when {
            failures > 0 && !screenOn && mobile -> SCREEN_OFF_MOBILE_FAIL_MS
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
