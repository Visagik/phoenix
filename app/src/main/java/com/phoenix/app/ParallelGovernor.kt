package com.phoenix.app

import java.io.File

/**
 * Запоминает по каждой сети, сколько параллельных проверок она выдерживает. Если во время тяжёлого поиска (или сразу после него)
 * у сети пропал интернет — вероятно, роутер или оператор принял поток проверок за атаку: уровень сети повышается
 * (меньше параллельных соединений). После нескольких спокойных тяжёлых прогонов подряд уровень снова понижается.
 * Файл: сеть, уровень, спокойных прогонов подряд, время последнего окончания тяжёлой проверки (мс).
 */
class ParallelGovernor(private val file: File) {
    private data class Entry(var level: Int, var clean: Int, var lastEnd: Long, var active: Int, var lastBegin: Long = 0L)

    private val map: MutableMap<String, Entry> by lazy { read() }

    private fun read(): MutableMap<String, Entry> {
        val m = LinkedHashMap<String, Entry>()
        try {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 4) return@forEachLine
                m[p[0]] = Entry(p[1].toIntOrNull()?.coerceIn(0, MAX_LEVEL) ?: 0, p[2].toIntOrNull() ?: 0, p[3].toLongOrNull() ?: 0L, 0)
            }
        } catch (_: Exception) {
        }
        return m
    }

    @Synchronized
    fun save() {
        try {
            file.parentFile?.mkdirs()
            val rows = map.entries.filter { it.value.level > 0 || it.value.clean > 0 }.toList().takeLast(MAX_NETS)
            file.writeTextAtomic(rows.joinToString("") { (n, e) -> "${n.replace('\t', ' ')}\t${e.level}\t${e.clean}\t${e.lastEnd}\n" })
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun level(net: String): Int = map[net]?.level ?: 0

    /** Сколько параллельных TCP-проверок разрешено в этой сети. */
    fun tcp(net: String): Int = TCP_LEVELS[level(net)]

    /** Сколько параллельных проверок через ядро; [base] — обычное значение для телефона. */
    fun probe(net: String, base: Int): Int = (base shr level(net)).coerceAtLeast(MIN_PROBE).coerceAtMost(base)

    @Synchronized
    fun begin(net: String, now: Long = System.currentTimeMillis()) {
        val e = map.getOrPut(net) { Entry(0, 0, 0L, 0) }
        // прогон, который так и не завершился (задачу отменили), не должен считаться идущим вечно
        if (e.active > 0 && now - e.lastBegin > STALE_MS) e.active = 0
        e.active++
        e.lastBegin = now
    }

    /** Сколько тяжёлых прогонов идёт в сети (для тестов и журнала). */
    @Synchronized
    fun activeCount(net: String): Int = map[net]?.active ?: 0

    /** Тяжёлый прогон закончился без обрыва сети. */
    @Synchronized
    fun finish(net: String, now: Long): Boolean {
        val e = map.getOrPut(net) { Entry(0, 0, 0L, 0) }
        if (e.active > 0) e.active--
        e.lastEnd = now
        // другой прогон в этой сети ещё идёт: спокойным считаем только когда закончили все
        if (e.active > 0) return false
        var lowered = false
        if (e.level > 0) {
            e.clean++
            if (e.clean >= CLEAN_TO_LOWER) { e.level--; e.clean = 0; lowered = true }
        }
        return lowered
    }

    /**
     * Сеть потеряла интернет. Если тяжёлая проверка идёт или закончилась меньше [WINDOW_MS] назад — это вероятная причина.
     * @return true, если уровень повышен.
     */
    @Synchronized
    fun outage(net: String, now: Long): Boolean {
        val e = map[net] ?: return false
        val running = e.active > 0 && now - e.lastBegin <= STALE_MS
        val recent = running || (e.lastEnd > 0 && now - e.lastEnd in 0..WINDOW_MS)
        if (!recent) return false
        e.active = 0
        e.clean = 0
        e.lastEnd = 0L
        if (e.level >= MAX_LEVEL) return false
        e.level++
        return true
    }

    companion object {
        /** На мобильной сети радиомодуль греется и дорожает от частых соединений: не больше [MOBILE_TCP_CAP] параллельных проверок. */
        const val MOBILE_TCP_CAP = 16
        fun capFor(tcp: Int, mobile: Boolean): Int = if (mobile) minOf(tcp, MOBILE_TCP_CAP) else tcp

        const val MAX_LEVEL = 3
        const val MIN_PROBE = 2
        const val CLEAN_TO_LOWER = 5
        const val WINDOW_MS = 90_000L
        const val STALE_MS = 40 * 60_000L
        const val MAX_NETS = 60
        val TCP_LEVELS = intArrayOf(48, 24, 12, 8)
    }
}
