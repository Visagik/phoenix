package com.phoenix.app

/**
 * Какие сервисы доступны из страны выхода. Порядок: TG, QW, CL, YT, ST.
 * Страны, которых нет в таблице, считаются «ok» по всем сервисам (как в service_blocks.tsv).
 */
object ServiceBlocks {
    val LABELS = listOf("TG", "QW", "CL", "YT", "ST")

    private val table: Map<String, List<String>> = buildMap {
        for (cc in listOf("RU", "CN", "IR", "KP", "BY", "SA")) {
            put(cc, List(5) { "blocked" })
        }
        put("TR", listOf("ok", "warn", "blocked", "ok", "ok"))
        put("AE", listOf("ok", "ok", "blocked", "ok", "ok"))
        put("IN", listOf("ok", "ok", "warn", "ok", "ok"))
    }

    fun status(cc: String): List<String> = table[cc] ?: List(5) { "ok" }
}

/**
 * Сервисы, закрытые по стране выхода (Claude, ChatGPT…): страница-заглушка для региона бывает с кодом 200,
 * поэтому по ответу сайта этого не понять. Страну выхода узнаём отдельно (Cloudflare trace через тот же сервер).
 */
object GeoGate {
    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    private fun matches(h: String, list: List<String>) = list.any { h == it || h.endsWith(".$it") }

    /** Сайт из списка «закрыт по стране выхода» (правила — см. [GeoRules]). */
    fun applies(url: String): Boolean = matches(hostOf(url), GeoRules.current.hosts)

    /** true — из этой страны сервис закрыт. */
    fun blocks(url: String, cc: String?): Boolean {
        if (!applies(url) || cc == null) return false
        val r = GeoRules.current
        val set = if (matches(hostOf(url), r.aiHosts)) r.closedAi else r.closed
        return cc.uppercase() in set
    }

    /** «loc=NL» из ответа cdn-cgi/trace. */
    fun parseLoc(trace: String): String? =
        Regex("(?m)^loc=([A-Za-z]{2})\\s*$").find(trace)?.groupValues?.get(1)?.uppercase()
}
