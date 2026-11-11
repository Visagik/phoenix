package com.phoenix.app

import java.io.File

/**
 * Правила «какие сайты закрыты по стране выхода»: список сайтов, закрытые страны, признаки страницы-заглушки.
 * Встроенный набор лежит в коде; поверх него можно положить свой файл `geogate_rules.txt` (его же можно скачивать по ссылке из настроек):
 * если файл испорчен или пуст, действует встроенный набор. Формат — по строке:
 *   host: claude.ai          — сайт, у которого проверяется страна и страница
 *   ai-host: claude.ai       — сайт со своим (более узким) списком стран
 *   closed: RU,CN            — страны, закрытые для сайтов из host
 *   ai-closed: HK,MO         — страны, закрытые для ai-host
 *   marker: <регулярное выражение> — фраза на странице-заглушке («не поддерживается в вашей стране»)
 *   path: /unavailable       — часть адреса, на который перенаправляет заглушка
 */
class GeoRules(
    val hosts: List<String>,
    val closed: Set<String>,
    val aiHosts: List<String>,
    val closedAi: Set<String>,
    val markers: List<Regex>,
    val pathHints: List<String>,
) {
    companion object {
        val DEFAULT = GeoRules(
            hosts = listOf(
                "claude.ai", "anthropic.com", "chatgpt.com", "openai.com", "sora.com", "gemini.google.com", "bard.google.com", "aistudio.google.com",
                "ai.google.dev", "notebooklm.google.com", "notebook.google", "labs.google", "copilot.microsoft.com", "grok.com", "x.ai", "meta.ai", "perplexity.ai",
            ),
            closed = setOf("RU", "CN", "IR", "KP", "BY", "SY", "CU"),
            // Claude и OpenAI не работают ещё и из Гонконга и Макао (по списку поддерживаемых стран; он меняется)
            aiHosts = listOf("claude.ai", "anthropic.com", "chatgpt.com", "openai.com", "sora.com"),
            closedAi = setOf("RU", "CN", "IR", "KP", "BY", "SY", "CU", "HK", "MO"),
            markers = listOf(
                "(isn['’]?t|is not|not)\\s+(currently\\s+)?(supported|available)\\s+(for you\\s+)?in\\s+your\\s+(country|region|location|territory)",
                "(unavailable|unsupported)\\s+in\\s+your\\s+(country|region|location)",
                "your\\s+(country|region)\\s+(isn['’]?t|is not)\\s+(currently\\s+)?supported",
                "(is|are)\\s+not\\s+(yet\\s+)?available\\s+in\\s+your\\s+(country|region)",
                "(недоступ\\S*|не\\s+поддерживается|не\\s+доступен|не\\s+работает)\\s+в\\s+(вашей\\s+)?(стране|регионе)",
                "в\\s+вашей\\s+(стране|регионе).{0,40}(недоступ|не\\s+поддерж)",
                "no\\s+est[aá]\\s+disponible\\s+en\\s+tu\\s+(pa[ií]s|regi[oó]n)",
                "n['’]est\\s+pas\\s+(disponible|pris\\s+en\\s+charge)\\s+dans\\s+votre\\s+pays",
                "nicht\\s+in\\s+deinem\\s+land\\s+verf[uü]gbar",
            ).map { Regex(it, setOf(RegexOption.IGNORE_CASE)) },
            pathHints = listOf("/unavailable", "/unsupported", "/not-available", "/country-not-supported", "/region-not-supported", "/regionblock"),
        )

        @Volatile var current: GeoRules = DEFAULT

        /** Разбор текста правил; null, если нет ни одного сайта (файл пустой или чужой). */
        fun parse(text: String): GeoRules? {
            val hosts = ArrayList<String>()
            val aiHosts = ArrayList<String>()
            var closed: Set<String>? = null
            var closedAi: Set<String>? = null
            val markers = ArrayList<Regex>()
            val paths = ArrayList<String>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                val i = line.indexOf(':')
                if (i <= 0) continue
                val key = line.substring(0, i).trim().lowercase()
                val v = line.substring(i + 1).trim()
                if (v.isEmpty()) continue
                when (key) {
                    "host" -> hosts += v.lowercase()
                    "ai-host" -> aiHosts += v.lowercase()
                    "closed" -> closed = (closed ?: emptySet()) + v.split(',', ' ').map { it.trim().uppercase() }.filter { it.length == 2 }
                    "ai-closed" -> closedAi = (closedAi ?: emptySet()) + v.split(',', ' ').map { it.trim().uppercase() }.filter { it.length == 2 }
                    "marker" -> try { if (v.length <= 300) markers += Regex(v, setOf(RegexOption.IGNORE_CASE)) } catch (_: Exception) { }
                    "path" -> paths += v.lowercase()
                }
            }
            if (hosts.isEmpty()) return null
            return GeoRules(
                hosts = hosts,
                closed = closed ?: DEFAULT.closed,
                aiHosts = aiHosts,
                closedAi = (closedAi ?: DEFAULT.closedAi),
                markers = markers.ifEmpty { DEFAULT.markers },
                pathHints = paths.ifEmpty { DEFAULT.pathHints },
            )
        }

        /** Подхватывает файл правил (если он есть и корректен); иначе остаётся встроенный набор. @return true — действуют правила из файла */
        fun reload(file: File): Boolean {
            val parsed = try { if (file.exists() && file.length() in 1..65_536) parse(file.readText()) else null } catch (_: Throwable) { null }
            current = parsed ?: DEFAULT
            return parsed != null
        }
    }
}

/** Страница сайта, открывшаяся через сервер: не «заглушка ли это про страну». */
object RegionBlock {
    /** Параметры адреса заглушки: location=unsupported, region=blocked, unsupported_country и подобные. */
    private val QUERY_HINT = Regex("(location|region|country|geo|reason|error)=(unsupported|unavailable|blocked|not[-_]?supported|not[-_]?available)|unsupported[-_]?(country|region|location)")

    private fun normalize(s: String): String = s
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'").replace("\\u0027", "'").replace("\\x27", "'")
        .replace("’", "'").replace("&nbsp;", " ").replace(" ", " ")

    /**
     * true — в цепочке переходов был антибот-узел Google (`www.google.com /sorry/…`): Google считает адрес выхода подозрительным
     * (журнал 07.11: Gemini через Финляндию и Великобританию шёл через /sorry/index и у пользователя не работал, через Польшу без неё).
     * [hops] — записи вида «узел путь», как их отдаёт LocalResponse.hops.
     */
    fun abuseChain(hops: List<String>): Boolean =
        hops.any { h -> h.startsWith("www.google.") && h.substringAfter(' ', "").startsWith("/sorry") }

    /** Закрыт ли сервис для этого выхода: заглушка региона либо антибот-страница Google по пути. */
    fun closed(r: LocalResponse, body: String): Boolean = blocked(r.finalUrl, body) || abuseChain(r.hops)

    /** true — по адресу, на который привело перенаправление, или по тексту страницы видно, что сервис закрыт для страны выхода. */
    fun blocked(finalUrl: String, body: String, rules: GeoRules = GeoRules.current): Boolean {
        val uri = try { java.net.URI(finalUrl) } catch (_: Exception) { null }
        val path = uri?.path.orEmpty().lowercase()
        if (path.isNotEmpty() && rules.pathHints.any { path.contains(it) }) return true
        // NotebookLM: код 200 и перенаправление на notebook.google/?location=unsupported — заглушка видна только по параметру адреса (журнал 05.11)
        val query = uri?.rawQuery.orEmpty().lowercase()
        if (query.isNotEmpty() && QUERY_HINT.containsMatchIn(query)) return true
        if (body.isEmpty()) return false
        val text = normalize(body)
        return rules.markers.any { it.containsMatchIn(text) } || Soft.regionBlocked(text)
    }
}

/** Короткая выдержка страницы для журнала: заголовок и начало видимого текста (чтобы по журналу подобрать признаки заглушки). */
object PageSnippet {
    fun of(body: String, max: Int = 240): String {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val text = body
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return (if (title.isNotEmpty()) "«${title.take(80)}» " else "") + text.take(max)
    }
}
