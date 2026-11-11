package com.phoenix.app

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Вход в параллельную работу с «мягким стартом» и откатом: сначала пускает мало задач, добавляет по мере спокойной работы шлюза
 * и при перегрузке делит предел пополам, а потом ставит паузу с растущим сроком и случайным разбросом (чтобы телефон и роутер
 * не пытались восстановиться в один и тот же миг).
 */
class AdaptiveGate(
    start: Int,
    private val max: Int,
    private val min: Int = MIN_LIMIT,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
) {
    @Volatile var limit: Int = start.coerceIn(minOf(min, max), max)
        private set
    private val active = AtomicInteger()
    @Volatile private var pauseUntil = 0L
    private var busyStreak = 0
    private var okStreak = 0

    /** Сколько раз предел снижался: нужно для журнала. */
    @Volatile var throttled = 0
        private set

    suspend fun <T> run(block: suspend () -> T): T {
        while (true) {
            val t = now()
            if (t < pauseUntil) { delay(minOf(pauseUntil - t, 200L)); continue }
            val a = active.get()
            if (a < limit && active.compareAndSet(a, a + 1)) break
            delay(15)
        }
        try {
            return block()
        } finally {
            active.decrementAndGet()
        }
    }

    /** Шлюз перегружен: предел вдвое меньше, пауза 1 → 2 → 4 → 8 с плюс разброс до половины секунды. */
    @Synchronized
    fun busy() {
        okStreak = 0
        busyStreak++
        limit = maxOf(minOf(min, max), limit / 2)
        throttled++
        val pause = minOf(MAX_PAUSE_MS, BASE_PAUSE_MS shl (busyStreak - 1).coerceAtMost(4)) + (random() * 500).toLong()
        pauseUntil = now() + pause
    }

    /** Шлюз спокоен: после [OKS_TO_GROW] спокойных замеров подряд предел растёт. */
    @Synchronized
    fun ok() {
        busyStreak = 0
        if (limit >= max) return
        okStreak++
        if (okStreak >= OKS_TO_GROW) {
            okStreak = 0
            limit = minOf(max, limit + GROW_STEP)
        }
    }

    companion object {
        const val MIN_LIMIT = 4
        const val BASE_PAUSE_MS = 1_000L
        const val MAX_PAUSE_MS = 8_000L
        const val OKS_TO_GROW = 2
        const val GROW_STEP = 4

        /** С какого предела начинать: на всех сетях мягкий старт с [SLOW_START], но не выше потолка. */
        const val SLOW_START = 8
    }
}

/**
 * Оценка перегрузки шлюза (роутера) по времени ответа на быстрое соединение с ним.
 * Первые замеры — калибровка: если шлюз вообще не отвечает (мобильные сети), слежение отключается и ничего не тормозит.
 */
class GatewayLoad {
    enum class Verdict { OK, BUSY }

    private var samples = 0
    private var baseline = Long.MAX_VALUE
    private var answered = 0
    private val window = ArrayDeque<Long>()

    /** Слежение отключено: шлюз не отвечает на замеры. */
    var disabled = false
        private set

    /** [rttMs] — время ответа в мс или отрицательное число, если ответа не было. */
    @Synchronized
    fun feed(rttMs: Long): Verdict {
        samples++
        if (rttMs >= 0) {
            answered++
            if (samples <= CALIBRATION) baseline = minOf(baseline, rttMs)
        }
        if (samples == CALIBRATION && answered == 0) disabled = true
        if (disabled || samples <= CALIBRATION) return Verdict.OK
        window.addLast(rttMs)
        if (window.size > WINDOW) window.removeFirst()
        if (rttMs < 0) return Verdict.BUSY
        val limit = maxOf(MIN_BUSY_MS, baseline * FACTOR)
        if (rttMs > limit) return Verdict.BUSY
        // потери: роутер, который теряет каждый четвёртый пакет, уже перегружен, даже если отвечавшие замеры быстрые
        if (window.size >= MIN_WINDOW && window.count { it < 0 } * 4 >= window.size) return Verdict.BUSY
        // джиттер: разброс ответов в окне намного больше обычного
        val ok = window.filter { it >= 0 }
        if (ok.size >= MIN_WINDOW && ok.max() - ok.min() >= maxOf(MIN_JITTER_MS, baseline * JITTER_FACTOR)) return Verdict.BUSY
        return Verdict.OK
    }

    companion object {
        const val CALIBRATION = 3
        const val MIN_BUSY_MS = 150L
        const val FACTOR = 5L
        const val WINDOW = 8
        const val MIN_WINDOW = 6
        const val MIN_JITTER_MS = 250L
        const val JITTER_FACTOR = 10L
    }
}
