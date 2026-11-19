package com.phoenix.app

import java.io.File

/**
 * Ручная поправка: «на этом сервере сервис не открывается» (страница «не поддерживается в вашей стране» и т. п., которую приложение
 * не распознало само). Запоминается по связке сервер + сайт на несколько дней; перепроверки такие связки не засчитывают.
 */
class ServiceVeto private constructor(private val file: File) {
    companion object {
        const val TTL_MS = 3 * 24 * 60 * 60_000L

        private val cache = java.util.concurrent.ConcurrentHashMap<String, ServiceVeto>()
        operator fun invoke(file: File): ServiceVeto = cache.getOrPut(file.absolutePath) { ServiceVeto(file) }

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

        /** У NotebookLM три адреса (notebooklm.google.com, notebooklm.google, notebook.google): для пометок и сервисов это один сайт (журнал 19.11: «Не работает» ставилось на один адрес, а сервис «Notebook» был задан другим — галочка оставалась). */
        fun canon(host: String): String = when (host.lowercase()) {
            "notebooklm.google.com", "notebooklm.google", "notebook.google" -> "notebooklm.google.com"
            else -> host.lowercase()
        }

        fun sameSite(a: String, b: String): Boolean {
            val x = canon(a)
            val y = canon(b)
            return x == y || x.endsWith(".$y") || y.endsWith(".$x")
        }
    }

    private class Entry(val key: String, val host: String, val until: Long)

    @Synchronized
    private fun load(now: Long): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { l ->
                val p = l.split('\t')
                val until = p.getOrNull(2)?.toLongOrNull()
                if (p.size >= 3 && until != null && until > now) Entry(p[0], p[1], until) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(link: String, urlOrHost: String, now: Long = System.currentTimeMillis()) {
        val host = if (urlOrHost.contains("://")) hostOf(urlOrHost) else urlOrHost.lowercase()
        val key = CheckStore.keyOf(link)
        val keep = load(now).filter { !(it.key == key && sameSite(it.host, host)) }
        val all = keep + Entry(key, host, now + TTL_MS)
        try {
            file.parentFile?.mkdirs()
            file.writeTextAtomic(all.joinToString("") { "${it.key}\t${it.host}\t${it.until}\n" })
        } catch (_: Exception) {
        }
    }

    fun active(link: String, urlOrHost: String, now: Long = System.currentTimeMillis()): Boolean {
        val host = if (urlOrHost.contains("://")) hostOf(urlOrHost) else urlOrHost.lowercase()
        val key = CheckStore.keyOf(link)
        return load(now).any { it.key == key && sameSite(it.host, host) }
    }
}
