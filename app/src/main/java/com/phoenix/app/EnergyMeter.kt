package com.phoenix.app

import java.io.File

/**
 * Счётчик «сколько работы приложение делает само»: проверки туннеля, замеры скорости, проверенные при поиске серверы — по дням.
 * Объём трафика — оценка (по размеру типичного запроса), а не точное измерение. Хранится 14 дней; на диск пишется не чаще раза в 5 минут.
 */
class EnergyMeter(private val file: File) {
    data class Day(val health: Long = 0, val speed: Long = 0, val search: Long = 0, val bytes: Long = 0, val fetch: Long = 0, val targets: Long = 0, val diag: Long = 0)

    private val days = LinkedHashMap<String, Day>()
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L

    companion object {
        const val KEEP_DAYS = 14
        const val SAVE_EVERY_MS = 5 * 60_000L
        const val HEALTH_BYTES = 2_000L
        const val SPEED_BYTES = 300_000L
        const val SEARCH_BYTES = 3_000L
        const val FETCH_BYTES = 80_000L
        const val TARGETS_BYTES = 4_000L
        /** Прогон DIAG (Gemini/NotebookLM): ~6 запросов (Cloudflare trace + узлы из GoogleDiag.urls), пока сервис открыт. */
        const val DIAG_BYTES = 40_000L

        private val cache = java.util.concurrent.ConcurrentHashMap<String, EnergyMeter>()
        fun of(file: File): EnergyMeter = cache.getOrPut(file.absolutePath) { EnergyMeter(file) }

        fun mb(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} КБ" else "%.1f МБ".format(java.util.Locale.US, bytes / 1048576.0)
    }

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            file.forEachLine { l ->
                val p = l.split('\t')
                if (p.size >= 5) {
                    days[p[0]] = Day(p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0,
                        p.getOrNull(5)?.toLongOrNull() ?: 0, p.getOrNull(6)?.toLongOrNull() ?: 0, p.getOrNull(7)?.toLongOrNull() ?: 0)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** [kind]: health, speed, search, fetch (скачивание источников), targets (проверки сервисов целей) или diag (DIAG Google — раньше не считался вообще); [count] — сколько проверок. */
    @Synchronized
    fun add(kind: String, count: Int, day: String, now: Long) {
        if (count <= 0) return
        load()
        val d = days[day] ?: Day()
        val n = count.toLong()
        days[day] = when (kind) {
            "health" -> d.copy(health = d.health + n, bytes = d.bytes + n * HEALTH_BYTES)
            "speed" -> d.copy(speed = d.speed + n, bytes = d.bytes + n * SPEED_BYTES)
            "search" -> d.copy(search = d.search + n, bytes = d.bytes + n * SEARCH_BYTES)
            "fetch" -> d.copy(fetch = d.fetch + n, bytes = d.bytes + n * FETCH_BYTES)
            "targets" -> d.copy(targets = d.targets + n, bytes = d.bytes + n * TARGETS_BYTES)
            "diag" -> d.copy(diag = d.diag + n, bytes = d.bytes + n * DIAG_BYTES)
            else -> return
        }
        dirty = true
        if (now - lastSave >= SAVE_EVERY_MS) save(now)
    }

    @Synchronized
    fun save(now: Long) {
        if (!dirty) return
        val keep = days.keys.sortedDescending().take(KEEP_DAYS).toSet()
        days.keys.retainAll(keep)
        try {
            file.writeTextAtomic(days.entries.sortedBy { it.key }.joinToString("") { (k, d) -> "$k\t${d.health}\t${d.speed}\t${d.search}\t${d.bytes}\t${d.fetch}\t${d.targets}\t${d.diag}\n" })
            dirty = false
            lastSave = now
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun day(day: String): Day {
        load()
        return days[day] ?: Day()
    }

    /** «проверок туннеля 120, замеров скорости 3, проверено серверов 450; около 1.4 МБ». */
    fun text(day: String): String {
        val d = day(day)
        if (d.health + d.speed + d.search + d.fetch + d.targets + d.diag == 0L) return "ничего не делалось"
        return "проверок туннеля ${d.health}, замеров скорости ${d.speed}, проверено серверов ${d.search}" +
            (if (d.fetch > 0) ", скачано источников ${d.fetch}" else "") + (if (d.targets > 0) ", проверок сервисов целей ${d.targets}" else "") +
            (if (d.diag > 0) ", проверок Google (Gemini/NotebookLM) ${d.diag}" else "") + "; около ${mb(d.bytes)}"
    }
}
