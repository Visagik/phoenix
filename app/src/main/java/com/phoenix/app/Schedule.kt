package com.phoenix.app

import java.time.LocalDateTime
import java.time.LocalTime

/** Расчёт времени запусков. Не зависит от Android — проверяется тестами на JVM. */
object Schedule {
    /** Через сколько минут после назначенного времени «догоняющий» запуск считает слот пропущенным. */
    const val CATCH_UP_GRACE_MINUTES = 10L

    /** Минимальный перерыв между попытками догона, чтобы не запускать сбор при каждом открытии приложения. */
    const val CATCH_UP_MIN_GAP_MS = 30 * 60 * 1000L

    /** Ближайший будущий запуск в [hour]:[minute]: сегодня, если время ещё не прошло, иначе завтра. */
    fun nextRun(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        var next = now.toLocalDate().atTime(hour, minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    /**
     * Нужно ли запустить сбор «при первой возможности».
     * Да, если сегодняшнего файла нет, назначенное время сегодня уже прошло (с запасом) и недавно попыток не было.
     * Так сбор догоняется после выключенного телефона или убитого системой фона.
     */
    fun catchUpNeeded(
        now: LocalDateTime,
        hour: Int,
        minute: Int,
        doneToday: Boolean,
        lastAttemptMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (doneToday) return false
        val due = LocalTime.of(hour, minute).plusMinutes(CATCH_UP_GRACE_MINUTES)
        // если запас переваливает за полночь, plusMinutes даёт «раннее» время — тогда слот сегодня не наступил
        if (due.isBefore(LocalTime.of(hour, minute))) return false
        if (now.toLocalTime().isBefore(due)) return false
        if (lastAttemptMillis > 0 && nowMillis - lastAttemptMillis < CATCH_UP_MIN_GAP_MS) return false
        return true
    }

    // ---- сбор «раз в N часов» ----

    const val HOUR_MS = 3_600_000L

    /** Через сколько повторять, если срок уже прошёл, а сбор не удался или не запускался. */
    const val INTERVAL_RETRY_MS = 30 * 60 * 1000L

    /** Через сколько повторять, если сети не было (в логе 05.11: будильник в 14:27 без сети, следующий срок — через 12 ч). */
    const val NO_NETWORK_RETRY_MS = 10 * 60 * 1000L

    /** Через сколько ставить повторный будильник после неудачного сбора или пропуска из-за сети. */
    fun retryDelayMs(noNetwork: Boolean): Long = if (noNetwork) NO_NETWORK_RETRY_MS else INTERVAL_RETRY_MS

    /** Нужен ли повторный будильник: срок (или сегодняшний сбор) ещё не выполнен. */
    fun needsRetry(interval: Boolean, lastSuccessMs: Long, nowMs: Long, hours: Int, doneToday: Boolean): Boolean =
        if (interval) intervalDue(lastSuccessMs, nowMs, hours) else !doneToday

    /**
     * Пора ли собирать: ни разу не собирали, с последнего удачного сбора прошло [hours] часов, или отметка
     * последнего сбора почему-то в будущем (часы телефона переводили вперёд, потом поправили) — такая отметка
     * недостоверна, и без этого условия сбор мог бы не наступить очень долго.
     */
    fun intervalDue(lastSuccessMs: Long, nowMs: Long, hours: Int): Boolean =
        lastSuccessMs <= 0L || lastSuccessMs > nowMs || nowMs - lastSuccessMs >= hours.coerceAtLeast(1) * HOUR_MS

    /** Когда ставить будильник: срок после последнего удачного сбора, а если он уже прошёл — через [INTERVAL_RETRY_MS]. */
    fun nextIntervalAt(lastSuccessMs: Long, nowMs: Long, hours: Int): Long {
        val due = if (lastSuccessMs <= 0L) 0L else lastSuccessMs + hours.coerceAtLeast(1) * HOUR_MS
        return if (due > nowMs) due else nowMs + INTERVAL_RETRY_MS
    }

    /** Догон в режиме интервала: срок вышел и недавно попыток не было (отметка попытки в будущем тоже не в счёт). */
    fun intervalCatchUp(lastSuccessMs: Long, lastAttemptMs: Long, nowMs: Long, hours: Int): Boolean {
        if (!intervalDue(lastSuccessMs, nowMs, hours)) return false
        return lastAttemptMs <= 0L || lastAttemptMs > nowMs || nowMs - lastAttemptMs >= CATCH_UP_MIN_GAP_MS
    }
}
