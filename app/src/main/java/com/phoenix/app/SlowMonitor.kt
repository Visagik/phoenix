package com.phoenix.app

/**
 * Решает, пора ли менять сервер из-за низкой скорости. Замеры приходят редко и только когда телефоном реально пользуются.
 * Меняем после нескольких медленных замеров подряд, не чаще раза в [cooldownMs] и не больше [maxPerHour] раз в час —
 * иначе при плохой сети (когда медленно везде) приложение бесконечно прыгало бы с сервера на сервер.
 */
class SlowMonitor(
    var minMbps: Double,
    private val strikesNeeded: Int = 2,
    private val cooldownMs: Long = 5 * 60_000L,
    private val maxPerHour: Int = 4,
) {
    private var strikes = 0
    private var lastSwitch = Long.MIN_VALUE / 2
    private val switches = ArrayDeque<Long>()

    fun reset() {
        strikes = 0
    }

    /** @return true — скорость низкая уже несколько замеров подряд, сервер пора менять. */
    fun report(mbps: Double, now: Long): Boolean {
        if (mbps >= minMbps) {
            strikes = 0
            return false
        }
        strikes++
        if (strikes < strikesNeeded) return false
        if (now - lastSwitch < cooldownMs) return false
        while (switches.isNotEmpty() && now - switches.first() > 3_600_000L) switches.removeFirst()
        return switches.size < maxPerHour
    }

    /** Можно ли сейчас менять сервер: прошла пауза и не исчерпан лимит в час (без учёта числа медленных замеров). */
    fun canSwitch(now: Long): Boolean {
        if (now - lastSwitch < cooldownMs) return false
        while (switches.isNotEmpty() && now - switches.first() > 3_600_000L) switches.removeFirst()
        return switches.size < maxPerHour
    }

    fun switched(now: Long) {
        lastSwitch = now
        switches.addLast(now)
        strikes = 0
    }

    companion object {
        /** Новый сервер должен быть заметно лучше, а не чуть-чуть: иначе смена не имеет смысла. */
        fun betterEnough(newMbps: Double, oldMbps: Double, minMbps: Double): Boolean =
            newMbps >= maxOf(minMbps * 2, oldMbps * 1.5)

        /** Хватает ли реального трафика, чтобы не тратить замер: если за период и так прошло быстрее порога, сервер не медленный. */
        fun passiveRateMbps(bytes: Long, seconds: Double): Double = if (seconds <= 0) 0.0 else bytes * 8.0 / seconds / 1_000_000.0
    }
}
