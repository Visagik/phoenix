package com.phoenix.app

/** Как долго держится сервер, выбранный вручную: 0 — пока пользователь сам не отключит или не сменит. */
object PinPolicy {
    val OPTIONS: List<Int> = listOf(0, 1, 6, 24)

    fun clamp(hours: Int): Int = if (hours in OPTIONS) hours else 0

    /** Закрепление, поставленное в [setAt], истекло к моменту [now] при лимите [hours] часов. */
    fun expired(setAt: Long, now: Long, hours: Int): Boolean =
        hours > 0 && setAt > 0 && now - setAt >= hours * 3_600_000L

    fun label(hours: Int): String = when (hours) {
        1 -> "1 час"
        6 -> "6 часов"
        24 -> "Сутки"
        else -> "Пока не отключу"
    }
}
