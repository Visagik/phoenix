package com.phoenix.app

/**
 * Шанс, что сервис откроется через конфиг. Свои проверки (ok/fail) сглаживаются средним по сервису ([prior]):
 * нет своих данных — показываем среднее, много данных — почти только свои.
 */
object ServiceOdds {
    private const val WEIGHT = 2.0

    /** Среднее по сервису у всех конфигов; нет данных — 0.5. */
    fun prior(ok: Int, fail: Int): Double = if (ok + fail == 0) 0.5 else (ok + 1.0) / (ok + fail + 2.0)

    fun chance(ok: Int, fail: Int, prior: Double): Double = (ok + prior * WEIGHT) / (ok + fail + WEIGHT)

    fun percent(ok: Int, fail: Int, prior: Double): Int = Math.round(chance(ok, fail, prior) * 100).toInt().coerceIn(0, 100)
}
