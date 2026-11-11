package com.phoenix.app

import java.io.File

/**
 * Настоящая страна выхода рабочих конфигов (по ответу Cloudflare через сам сервер), ключ — [CheckStore.keyOf].
 * Название сервера и таблица по IP — только догадка (у «🇷🇺 LTE …» вход в России, выход за границей).
 */
class ExitStore private constructor(private val file: File) {
    data class Exit(val cc: String, val date: String)

    @Synchronized
    fun load(): Map<String, Exit> {
        if (!file.exists()) return emptyMap()
        val map = LinkedHashMap<String, Exit>()
        file.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3 && p[1].length == 2) map[p[0]] = Exit(p[1].uppercase(), p[2])
        }
        return map
    }

    @Synchronized
    fun put(key: String, cc: String, date: String) = putAll(mapOf(key to Exit(cc.uppercase(), date)))

    @Synchronized
    fun putAll(items: Map<String, Exit>) {
        if (items.isEmpty()) return
        val merged = LinkedHashMap(load())
        merged.putAll(items)
        // не копим бесконечно: хранится не больше MAX записей, старые по дате уходят
        val kept = merged.entries.sortedByDescending { it.value.date }.take(MAX)
        file.parentFile?.mkdirs()
        file.writeTextAtomic(kept.joinToString("\n") { "${it.key}\t${it.value.cc}\t${it.value.date}" } + "\n")
    }

    /** Страна, известная не старше [maxAgeDays] дней, иначе null. */
    fun fresh(key: String, today: java.time.LocalDate, maxAgeDays: Long = FRESH_DAYS): String? {
        val e = load()[key] ?: return null
        val d = try { java.time.LocalDate.parse(e.date) } catch (_: Exception) { return null }
        return if (d.plusDays(maxAgeDays).isBefore(today)) null else e.cc
    }

    companion object {
        const val MAX = 3000
        const val FRESH_DAYS = 3L
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ExitStore>()
        operator fun invoke(file: File): ExitStore = cache.getOrPut(file.absolutePath) { ExitStore(file) }
    }
}
