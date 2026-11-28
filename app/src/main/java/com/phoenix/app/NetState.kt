package com.phoenix.app

/**
 * Единое состояние связи «сеть шатается»: раньше о нём судили по трём отдельным часам (`netShakeAt` в службе, `wobbleAt` в определителе,
 * `NetFlap` в `Resilience`) с окнами 60/120/120 с, разбросанными по коду. Теперь все источники только сообщают событие, а окна и вывод
 * («ждём», «сеть прыгает») живут здесь; `checkHealth`, перепроверка и фоновая задача читают одно и то же.
 */
object NetState {
    /** Сеть сменилась / система предупредила, что она пропадёт: сбой в это окно — вина сети. */
    const val SETTLE_MS = HealthGate.SETTLE_MS
    /** Дольше этого смена сети ещё считается «свежей» для запуска определения режима. */
    const val RECENT_MS = HealthGate.RECENT_MS
    /** Сколько держится отметка «сеть прыгает». */
    const val FLAP_HOLD_MS = 120_000L

    enum class Link { STABLE, SETTLING, FLAPPING }

    @Volatile var shakeAt = 0L
        private set
    @Volatile var wobbleAt = 0L
        private set
    @Volatile private var flapUntil = 0L

    /** Смена сети или предупреждение системы. */
    fun shake(now: Long) { shakeAt = now }
    /** Прямой замер ничего не открыл при живой сети («Wi-Fi дёрнуло»). */
    fun wobble(now: Long) { wobbleAt = now }
    /** Сеть меняла тип несколько раз подряд. */
    fun flap(now: Long) { flapUntil = now + FLAP_HOLD_MS }

    fun isSettling(now: Long): Boolean = HealthGate.settling(now, maxOf(shakeAt, wobbleAt))
    fun isFlapping(now: Long): Boolean = now < flapUntil && flapUntil - now <= FLAP_HOLD_MS
    /** Сеть меняли/предупредили о потере или она прыгает (без «дрожи замера», которую ставит само определение). */
    fun isShaky(now: Long): Boolean = HealthGate.settling(now, shakeAt) || isFlapping(now)

    /** Смена сети была недавно (для запуска определения режима на первой неудаче). */
    fun recentShake(now: Long): Boolean = HealthGate.recent(now, shakeAt)

    /** Вывод для решений: сначала «ждём», затем «прыгает». */
    fun link(now: Long): Link = when {
        isSettling(now) -> Link.SETTLING
        isFlapping(now) -> Link.FLAPPING
        else -> Link.STABLE
    }

    fun clear() { shakeAt = 0L; wobbleAt = 0L; flapUntil = 0L }
}
