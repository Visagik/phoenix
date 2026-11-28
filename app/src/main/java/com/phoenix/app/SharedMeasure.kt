package com.phoenix.app

/**
 * Итог прямого замера вместе с его причинами (Р1): раньше причины лежали в общих полях `ModeDetector`, и два замера разом стирали друг
 * другу причины. [why] — каждый НЕ открывшийся адрес → причина («DNS», «таймаут», «не успели»…); открывшихся в карте нет;
 * [epoch] — эпоха сети на момент замера.
 */
data class Measured(val counts: ProbeCounts, val why: Map<String, String>, val at: Long, val epoch: Long) {
    /** «DNS 3, таймаут 2»: сколько адресов не открылось по какой причине. */
    fun whySummary(): String = why.values.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }

    /** Адреса из [urls], не открывшиеся в этом замере, с причиной: «хост — причина». */
    fun failedWithWhy(urls: List<String>): String =
        urls.filter { it in why }.joinToString(", ") { u -> u.substringAfter("://").substringBefore('/') + " — " + why.getValue(u) }
}

/**
 * Один замер на всех (Р1): пока замер идёт, второй вызвавший ждёт его результат и не запускает свой. [key] — эпоха сети: замер, начатый в
 * другой сети, чужому вызывающему не подходит. Готовый результат не хранится: следующий вызов после окончания меряет заново.
 */
class SharedMeasure<T> {
    // CountDownLatch, а не CompletableFuture: тот есть только с Android 7, а Lite работает на Android 6 (как и в ProbeShare)
    private class Flight<T>(val key: Long) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var value: T? = null
        @Volatile var error: Throwable? = null
    }
    private var flight: Flight<T>? = null

    @Suppress("UNCHECKED_CAST")
    fun get(key: Long, compute: () -> T): T {
        val (mine, own) = synchronized(this) {
            val f = flight
            if (f != null && f.key == key) f to false else Flight<T>(key).also { flight = it } to true
        }
        if (!own) {
            mine.done.await()
            mine.error?.let { throw it }
            return mine.value as T
        }
        try {
            val r = compute()
            mine.value = r
            return r
        } catch (t: Throwable) {
            mine.error = t
            throw t
        } finally {
            synchronized(this) { if (flight === mine) flight = null }
            mine.done.countDown()
        }
    }
}
