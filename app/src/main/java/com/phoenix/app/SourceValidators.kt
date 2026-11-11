package com.phoenix.app

import java.io.File

/**
 * Метки версии содержимого источника (ETag, Last-Modified): при следующей загрузке они отправляются серверу, и если ничего не менялось,
 * он отвечает 304 без тела — трафик и время не тратятся. Хранится в файле `source_validators.tsv` (адрес, ETag, Last-Modified).
 */
class SourceValidators(private val file: File) {
    data class V(val etag: String, val lastModified: String)

    private val map = java.util.concurrent.ConcurrentHashMap<String, V>()

    init {
        try {
            if (file.exists()) for (line in file.readLines()) {
                val p = line.split('\t')
                if (p.size >= 3 && p[0].isNotEmpty()) map[p[0]] = V(p[1], p[2])
            }
        } catch (_: Exception) {
        }
    }

    fun get(url: String): V? = map[url]

    /** Запоминает метки из ответа; пустые не затирают прежние только когда обе пусты (тогда метки удаляются: сервер их больше не даёт). */
    fun put(url: String, etag: String, lastModified: String) {
        val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ').trim() }
        if (etag.isBlank() && lastModified.isBlank()) map.remove(url) else map[url] = V(clean(etag), clean(lastModified))
    }

    /** Оставляет метки только для текущих источников; возвращает, сколько удалено. */
    fun retainOnly(urls: Collection<String>): Int {
        val keep = urls.toHashSet()
        val drop = map.keys.filter { it !in keep }
        drop.forEach { map.remove(it) }
        return drop.size
    }

    fun forget(urls: Collection<String>) {
        urls.forEach { map.remove(it) }
    }

    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        file.writeTextAtomic(map.entries.sortedBy { it.key }.joinToString("") { (k, v) -> "$k\t${v.etag}\t${v.lastModified}\n" })
    }

    companion object {
        /** Какие заголовки добавить к запросу: пары имя → значение (пусто, если меток нет или кэша нет — тогда нужен полный ответ). */
        fun requestHeaders(v: V?, haveCache: Boolean): Map<String, String> {
            if (v == null || !haveCache) return emptyMap()
            val out = LinkedHashMap<String, String>()
            if (v.etag.isNotBlank()) out["If-None-Match"] = v.etag
            if (v.lastModified.isNotBlank()) out["If-Modified-Since"] = v.lastModified
            return out
        }
    }
}
