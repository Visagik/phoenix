package com.phoenix.app

import java.security.MessageDigest

/**
 * Личность конфига — протокол + имя (адрес у многих серверов меняется, имя остаётся). Одно и то же имя из РАЗНЫХ
 * ссылок-источников — разные серверы: без этого у них общая история, карантин, запреты и страна выхода.
 * Внутри одной ссылки то же имя между сборами — тот же сервер.
 *
 * Поэтому при сборе конфиг, чьё имя уже занял более ранний источник (источники берутся по порядку адресов, чтобы порядок
 * загрузки не менял результат), получает к имени метку источника «(ab12)». Первый источник остаётся без метки — у
 * уже накопленной истории ключи не меняются. Конфиги без `#имя` (например, vmess с именем внутри base64) не помечаются.
 */
object SourceTag {
    fun tagOf(sourceUrl: String): String =
        MessageDigest.getInstance("SHA-1").digest(sourceUrl.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)

    /** Дописывает метку к имени после `#`; без `#` возвращает строку как есть. */
    fun withTag(line: String, tag: String): String {
        val i = line.indexOf('#')
        return if (i < 0) line else line.trimEnd() + "%20($tag)"
    }

    /** @param perSource пары (адрес источника, строки его тела); возвращает все строки, у «чужих» дублей имён — с меткой. */
    fun apply(perSource: List<Pair<String, List<String>>>, keyOf: (String) -> String): List<String> {
        val owner = HashMap<String, String>()
        val out = ArrayList<String>()
        // сначала источники по адресу: кто «хозяин» имени, не зависит от того, в каком порядке они скачались
        val byUrl = perSource.sortedBy { it.first }
        val tagged = HashMap<String, List<String>>()
        for ((url, lines) in byUrl) {
            val res = ArrayList<String>(lines.size)
            for (raw in lines) {
                val line = raw.trim()
                if (!line.contains("://")) { res += raw; continue }
                val k = keyOf(line)
                val o = owner.getOrPut(k) { url }
                res += if (o == url) raw else withTag(line, tagOf(url))
            }
            tagged[url] = res
        }
        for ((url, _) in perSource) out += tagged[url].orEmpty()
        return out
    }
}
