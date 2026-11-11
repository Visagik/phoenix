package com.phoenix.app

/** Разбор строк журнала для раздела «Диагностика»: метки, фильтры, поиск, проблемы, сводка за период. */
object Diagnostics {
    /** Группы меток журнала: название → метки сообщений. */
    val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Всё" to emptyList(),
        "VPN" to listOf("VPN", "ENV", "HEALTH", "SOCKS"),
        "Режим и сеть" to listOf("DETECT", "MODE", "NET"),
        "Переключения" to listOf("FAILOVER", "SWITCH", "SLOW", "A", "B"),
        "Цели" to listOf("TARGET", "TARGETS"),
        "Проверки" to listOf("CHECK", "RECHECK", "SVC"),
        "Обход и источники" to listOf("BYPASS", "PRIO", "AUDIT", "OK", "FAIL", "DEDUP", "ENRICH", "TRACK", "DONE"),
    )

    private val problem = Regex("(?i)не работает|не удалось|ошибк|\\bFAIL\\b|неудача|не отвечает|заблокирован|прервано|нет сети|сбой|не нашл|не найдено")

    private val tagRegex = Regex("^([A-Z]+):")

    /** Содержательная часть строки без метки времени. */
    fun message(line: String): String = line.substringAfter(' ', line)

    /** «VPN», «TARGET», «A»… или пусто, если у сообщения нет метки. */
    fun tagOf(line: String): String = tagRegex.find(message(line))?.groupValues?.get(1).orEmpty()

    fun isProblem(line: String): Boolean = problem.containsMatchIn(message(line))

    fun filter(lines: List<String>, group: String, query: String, problemsOnly: Boolean): List<String> {
        val tags = GROUPS.firstOrNull { it.first == group }?.second.orEmpty()
        val q = query.trim()
        return lines.filter { line ->
            (tags.isEmpty() || tagOf(line) in tags) &&
                (!problemsOnly || isProblem(line)) &&
                (q.isEmpty() || line.contains(q, ignoreCase = true))
        }
    }

    /** Сколько событий каждого вида было (по строкам журнала). */
    data class Counts(
        val failovers: Int,
        val modeSwitches: Int,
        val networkChanges: Int,
        val routeFails: Int,
        val targetUpdates: Int,
        val problems: Int,
    )

    fun counts(lines: List<String>): Counts = Counts(
        failovers = lines.count { tagOf(it) == "FAILOVER" },
        modeSwitches = lines.count { tagOf(it) == "MODE" },
        networkChanges = lines.count { tagOf(it) == "NET" },
        routeFails = lines.count { tagOf(it) == "A" && message(it).contains("не отвечает") },
        targetUpdates = lines.count { tagOf(it) == "TARGET" && message(it).contains("рабочих серверов") && !message(it).contains("не найдено") },
        problems = lines.count { isProblem(it) },
    )

    /** Строки не старше [sinceIso] (ISO-время в начале строки сравнивается как текст, у обоих режимов один часовой пояс). */
    fun since(lines: List<String>, sinceIso: String): List<String> = lines.filter { it.substringBefore(' ') >= sinceIso }

    /** «2026-10-15T07:41:03.123+03:00 VPN: …» → «10-15 07:41:03  VPN: …» */
    fun pretty(line: String): String {
        val ts = line.substringBefore(' ')
        val msg = message(line)
        val short = if (ts.length >= 19 && ts[10] == 'T') ts.substring(5, 10) + " " + ts.substring(11, 19) else ts
        return "$short  $msg"
    }

    private val proxyLink = Regex("(?:vless|vmess|trojan|ss|hysteria2|hy2)://\\S+")
    private val urlPath = Regex("(https?://[^\\s/]+)(/[^\\s)\\]]*)")

    /** Это уже не секрет, а токен: длинный отрезок пути, где есть и буквы, и цифры (личные подписки вида /sub/aB3…). */
    private fun looksLikeToken(seg: String) = seg.length >= 16 && seg.any { it.isDigit() } && seg.any { it.isLetter() }

    /**
     * Текст журнала перед отправкой другому человеку: ключи доступа и личные токены ссылок-подписок заменяются на «…».
     * В самом приложении журнал остаётся полным.
     */
    fun redact(text: String): String =
        urlPath.replace(proxyLink.replace(text, "…ссылка скрыта…")) { m ->
            val path = m.groupValues[2].split('/').joinToString("/") { if (looksLikeToken(it)) "…" else it }
            m.groupValues[1] + path
        }

    /** Всё нужное для разбора ошибок одним текстом: сводка и последние строки журнала. */
    fun bundle(summary: List<Pair<String, String>>, counts: Counts, log: List<String>, limit: Int = 300): String = buildString {
        appendLine("PHOENIX DIAGNOSTICS")
        for ((k, v) in summary) appendLine("$k: $v")
        appendLine(
            "За сутки: переключений сервера ${counts.failovers}, смен режима ${counts.modeSwitches}, смен сети ${counts.networkChanges}, " +
                "сбоев маршрутов ${counts.routeFails}, обновлений целей ${counts.targetUpdates}, проблемных записей ${counts.problems}",
        )
        appendLine()
        log.takeLast(limit).forEach { appendLine(redact(pretty(it))) }
    }
}
