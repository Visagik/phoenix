package com.phoenix.app

import java.io.File

/**
 * Учится, как часто источник реально меняет содержимое, и в плановых сборах не скачивает его заново, пока рано.
 * Первые наблюдения — только обучение (скачивается всегда). Затем предположение «источник обновляется примерно раз в N часов»
 * проверяется: каждая четвёртая пропущенная загрузка делается всё равно, и если содержимое изменилось раньше срока, интервал пересчитывается.
 * Нужен сохранённый ответ источника (кэш): без него пропуск невозможен. Ручной сбор пропусков не делает.
 */
class SourceSchedule(private val file: File) {
    class Entry(val hash: String, val lastChange: Long, val changes: List<Long>, val skips: Int)

    private val map: MutableMap<String, Entry> by lazy { read() }

    private fun read(): MutableMap<String, Entry> {
        val m = LinkedHashMap<String, Entry>()
        try {
            if (file.exists()) file.forEachLine { l ->
                val p = l.split('\t')
                if (p.size < 5) return@forEachLine
                m[p[0]] = Entry(p[1], p[2].toLongOrNull() ?: return@forEachLine, p[4].split(',').mapNotNull { it.toLongOrNull() }, p[3].toIntOrNull() ?: 0)
            }
        } catch (_: Exception) {
        }
        return m
    }

    @Synchronized
    fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeTextAtomic(map.entries.joinToString("") { (u, e) -> "${u.replace('\t', ' ')}\t${e.hash}\t${e.lastChange}\t${e.skips}\t${e.changes.joinToString(",")}\n" })
        } catch (_: Exception) {
        }
    }

    /** Скачано: [hash] — отпечаток состава. Возвращает true, если состав изменился с прошлого раза. */
    @Synchronized
    fun record(url: String, hash: String, now: Long): Boolean {
        val old = map[url]
        if (old == null) {
            map[url] = Entry(hash, now, emptyList(), 0)
            return false
        }
        if (old.hash == hash) {
            map[url] = Entry(old.hash, old.lastChange, old.changes, 0)
            return false
        }
        map[url] = Entry(hash, now, (old.changes + now).takeLast(MAX_CHANGES), 0)
        return true
    }

    @Synchronized
    fun skip(url: String, now: Long, cacheOk: Boolean): Boolean = decide(map[url], now, cacheOk)

    @Synchronized
    fun noteSkip(url: String) {
        val e = map[url] ?: return
        map[url] = Entry(e.hash, e.lastChange, e.changes, e.skips + 1)
    }

    @Synchronized
    fun forget(keep: Set<String>) { map.keys.retainAll(keep) }

    @Synchronized
    fun intervalHours(url: String): Double? = medianInterval(map[url]?.changes.orEmpty())?.let { it / 3_600_000.0 }

    companion object {
        const val MAX_CHANGES = 8
        const val LEARN_CHANGES = 4
        const val MIN_INTERVAL_MS = 6 * 3_600_000L
        const val VERIFY_EVERY = 3

        fun medianInterval(changes: List<Long>): Long? {
            if (changes.size < 2) return null
            val d = changes.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
            return if (d.isEmpty()) null else d[d.size / 2]
        }

        /** true — можно взять из кэша: обучение закончено, интервал не короче 6 часов, рано, и каждая четвёртая загрузка всё равно настоящая. */
        fun decide(e: Entry?, now: Long, cacheOk: Boolean): Boolean {
            if (e == null || !cacheOk || e.changes.size < LEARN_CHANGES) return false
            val interval = medianInterval(e.changes) ?: return false
            if (interval < MIN_INTERVAL_MS || e.skips >= VERIFY_EVERY) return false
            return now >= e.lastChange && now - e.lastChange < interval / 2
        }
    }
}
