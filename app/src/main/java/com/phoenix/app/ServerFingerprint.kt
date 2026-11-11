package com.phoenix.app

import java.net.URLDecoder

/**
 * «Отпечаток» сервера: что у конфига общее с другим, даже если адрес записан иначе (другой домен, IP вместо домена).
 * Совпадение отпечатков не значит, что серверы одинаковы (у платных подписок один uuid на весь кластер),
 * поэтому по нему ничего не удаляется: он нужен для подсчёта вероятных повторов в отчёте сбора.
 * Основа: протокол + учётные данные (uuid / пароль) + ключ Reality + SNI + путь + вид транспорта.
 */
object ServerFingerprint {
    private fun query(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', "")
            if (k.isEmpty()) null else k.lowercase() to try { URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") } catch (_: Exception) { kv.substringAfter('=', "") }
        }.toMap()
    }

    /** Отпечаток ссылки или null, если разобрать нельзя либо данных мало (vmess в base64, ss без параметров). */
    fun of(link: String): String? {
        val p = LinkParser.parse(link) ?: return null
        if (p.protocol == "vmess") return null
        val body = p.urlPart.substringAfter("://")
        if (!body.contains('@')) return null
        val cred = body.substringBefore('@')
        if (cred.isBlank()) return null
        val q = query(p.urlPart)
        val key = listOf(q["pbk"], q["sni"] ?: q["host"], q["path"], q["type"], q["security"]).joinToString("|") { it.orEmpty() }
        return "${p.protocol}|$cred|${p.port ?: 0}|$key"
    }

    /** Группы ссылок с одним отпечатком, но разными адресами (host:port) — «вероятные повторы». Остальные группы не возвращаются. */
    fun likelyRepeats(links: List<String>): List<List<String>> {
        val byFp = LinkedHashMap<String, MutableList<ParsedLink>>()
        val raw = LinkedHashMap<String, MutableList<String>>()
        for (l in links) {
            val fp = of(l) ?: continue
            val p = LinkParser.parse(l) ?: continue
            byFp.getOrPut(fp) { mutableListOf() }.add(p)
            raw.getOrPut(fp) { mutableListOf() }.add(l)
        }
        return byFp.entries.filter { (_, v) -> v.map { it.address }.distinct().size > 1 }.map { raw.getValue(it.key) }
    }
}
