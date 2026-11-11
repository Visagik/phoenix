package com.phoenix.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Общий замер «сервер × адрес»: если такой же замер уже идёт (перепроверка, цели, сервисы, проверка в смене сервера запускаются
 * независимо и нередко берут один и тот же конфиг), второй вызов ждёт результат первого, а не гоняет ядро заново;
 * свежий результат (20 с, неудача — 5 с) тоже используется повторно.
 */
object ProbeShare {
    const val OK_TTL_MS = 20_000L
    const val FAIL_TTL_MS = 5_000L
    const val WAIT_MS = 40_000L

    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    private class Slot {
        private val latch = CountDownLatch(1)
        @Volatile var value = -1L
        @Volatile var doneAt = 0L
        val isDone: Boolean get() = latch.count == 0L
        fun complete(v: Long, at: Long) { value = v; doneAt = at; latch.countDown() }
        fun await(ms: Long): Boolean = latch.await(ms, TimeUnit.MILLISECONDS)
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** Сколько замеров выполнено без запуска ядра (для журнала и тестов). */
    private val savedCount = java.util.concurrent.atomic.AtomicInteger()
    val saved: Int get() = savedCount.get()

    /** Сколько завершённых записей храним: новые замеры вытесняют просроченные, а при переполнении — самые старые. */
    const val MAX_DONE = 2048
    private val sinceTrim = java.util.concurrent.atomic.AtomicInteger()

    fun reset() { slots.clear(); savedCount.set(0); sinceTrim.set(0) }

    /** Размер таблицы (для тестов и журнала). */
    internal fun size(): Int = slots.size

    /** Убирает просроченные и лишние завершённые записи; идущие замеры не трогает. */
    internal fun trim(now: Long) {
        slots.entries.removeIf { it.value.isDone && !fresh(it.value, now) }
        val done = slots.entries.filter { it.value.isDone }
        if (done.size > MAX_DONE) {
            done.sortedBy { it.value.doneAt }.take(done.size - MAX_DONE).forEach { slots.remove(it.key, it.value) }
        }
    }

    /** Полный отпечаток: String.hashCode() у разных конфигов одной длины может совпасть — тогда один получил бы замер другого. */
    internal fun keyOf(cfg: String, url: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(cfg.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2 + url.length + 1)
        for (b in d) { sb.append("0123456789abcdef"[(b.toInt() shr 4) and 15]); sb.append("0123456789abcdef"[b.toInt() and 15]) }
        return sb.append(':').append(url).toString()
    }

    private fun fresh(s: Slot, now: Long) = s.isDone && now - s.doneAt <= (if (s.value >= 0) OK_TTL_MS else FAIL_TTL_MS)

    fun measure(cfg: String, url: String, run: () -> Long): Long {
        val key = keyOf(cfg, url)
        while (true) {
            val now = clock()
            val cur = slots[key]
            if (cur == null) {
                val mine = Slot()
                if (slots.putIfAbsent(key, mine) != null) continue
                return lead(key, mine, run)
            }
            if (cur.isDone) {
                if (fresh(cur, now)) { savedCount.incrementAndGet(); return cur.value }
                val mine = Slot()
                if (!slots.replace(key, cur, mine)) continue
                return lead(key, mine, run)
            }
            // такой же замер идёт — ждём его итог; не дождались (завис) — меряем сами
            if (cur.await(WAIT_MS) && cur.isDone) { savedCount.incrementAndGet(); return cur.value }
            // ведущий завис: слот забирает один из ожидающих, остальные ждут уже его (раньше каждый запускал свой замер)
            val mine = Slot()
            if (!slots.replace(key, cur, mine)) continue
            return lead(key, mine, run)
        }
    }

    private fun lead(key: String, mine: Slot, run: () -> Long): Long {
        try {
            val v = run()
            mine.complete(v, clock())
            if (sinceTrim.incrementAndGet() >= 64) { sinceTrim.set(0); trim(clock()) }
            return v
        } catch (t: Throwable) {
            mine.complete(-1L, clock())
            slots.remove(key, mine)
            throw t
        }
    }
}
