package com.phoenix.app

import java.io.File

/**
 * Свои ссылки пользователя в пул источников (файл my_sources.txt, по ссылке в строке).
 * Добавляются к общему списку из sources.txt; чёрный список по-прежнему имеет приоритет.
 */
class UserSources(private val file: File) {

    fun load(): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && isValid(it) }
            .distinct()
    }

    /** @return null — добавлено; иначе текст ошибки для пользователя */
    fun add(raw: String): String? {
        val url = raw.trim()
        if (!isValid(url)) return "Нужна ссылка вида https://…"
        val current = load()
        if (url in current) return "Эта ссылка уже в списке"
        write(current + url)
        return null
    }

    /** Пакетное добавление: одно чтение и одна запись файла. @return сколько новых ссылок добавлено */
    fun addAll(raw: List<String>): Int {
        val current = load()
        val have = current.toHashSet()
        val fresh = ArrayList<String>()
        for (r in raw) {
            val u = r.trim()
            if (isValid(u) && have.add(u)) fresh.add(u)
        }
        if (fresh.isNotEmpty()) write(current + fresh)
        return fresh.size
    }

    fun remove(url: String): Boolean {
        val current = load()
        if (url !in current) return false
        write(current - url)
        return true
    }

    private fun write(list: List<String>) {
        file.writeTextAtomic(list.joinToString("") { it + "\n" })
    }

    companion object {
        fun isValid(s: String): Boolean {
            if (s.length < 10 || s.any { it.isWhitespace() }) return false
            return (s.startsWith("https://") || s.startsWith("http://")) && s.substringAfter("://").contains('.')
        }

        /** Достаёт первую http(s)-ссылку из каждой строки (как в sources.txt: после ссылки может быть комментарий). */
        /** Предел для внешнего импорта («Поделиться»): текст и число ссылок. */
        const val MAX_IMPORT_CHARS = 200_000
        const val MAX_IMPORT_LINKS = 200

        fun parseSourceList(text: String): List<String> = text.lineSequence()
            .mapNotNull { line ->
                line.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            }
            .distinct()
            .toList()
    }
}
