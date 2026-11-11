package com.phoenix.app

import java.io.File

/**
 * Мягкая оценка источников: источник понижают («низкий» автоприоритет) не после одной плохой перепроверки, а когда он
 * «мёртвым» выглядит несколько разных дней подряд и его содержимое за это время не обновлялось. Появились новые конфиги —
 * счётчик сбрасывается: источник заслужил новый шанс. Так приложение подстраивается под время, когда авторы обновляют списки.
 * Файл: url, хеш содержимого, день последнего изменения, число «плохих» дней, последний плохой день.
 */
class SourceCadence(private val file: File) {
    data class Entry(val hash: String, val changedDay: Long, val strikes: Int, val strikeDay: Long)

    private val map: MutableMap<String, Entry> by lazy { read() }

    private fun read(): MutableMap<String, Entry> {
        val m = LinkedHashMap<String, Entry>()
        try {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 5) return@forEachLine
                m[p[0]] = Entry(p[1], p[2].toLongOrNull() ?: return@forEachLine, p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0L)
            }
        } catch (_: Exception) {
        }
        return m
    }

    @Synchronized
    fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeTextAtomic(map.entries.joinToString("") { (u, e) -> "${u.replace('\t', ' ')}\t${e.hash}\t${e.changedDay}\t${e.strikes}\t${e.strikeDay}\n" })
        } catch (_: Exception) {
        }
    }

    /** Запомнить состав источника; если он изменился (появились новые конфиги) — счётчик «плохих» дней обнуляется. */
    @Synchronized
    fun observe(url: String, ids: Set<String>, day: Long) {
        val h = hashOf(ids)
        val old = map[url]
        map[url] = when {
            old == null -> Entry(h, day, 0, 0L)
            old.hash != h -> Entry(h, day, 0, 0L)
            else -> old
        }
    }

    /** Источник выглядит мёртвым сегодня: добавляет «плохой день» (не больше одного в сутки). Возвращает, пора ли понижать. */
    @Synchronized
    fun strike(url: String, day: Long): Boolean {
        val e = map[url] ?: Entry("", day, 0, 0L).also { map[url] = it }
        val n = if (e.strikeDay == day) e.strikes else e.strikes + 1
        map[url] = e.copy(strikes = n, strikeDay = day)
        return n >= STRIKES_TO_DEMOTE
    }

    /** Источник сегодня не выглядит мёртвым (или не оценивается): счётчик сбрасывается. */
    @Synchronized
    fun recover(url: String) {
        val e = map[url] ?: return
        if (e.strikes != 0) map[url] = e.copy(strikes = 0)
    }

    @Synchronized
    fun strikes(url: String): Int = map[url]?.strikes ?: 0

    @Synchronized
    fun forget(keep: Set<String>) {
        map.keys.retainAll(keep)
    }

    companion object {
        const val STRIKES_TO_DEMOTE = 3

        fun hashOf(ids: Set<String>): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(ids.sorted().joinToString(",").toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}
