package com.phoenix.app

data class ConfigRow(
    /** Полная строка (с меткой) — именно она копируется в V2RayNG */
    val raw: String,
    val protocol: String,
    /** Исходный фрагмент имени (URL-кодирован) — ключ трекера */
    val name: String,
    val cc: String?,
    /** TG, QW, CL, YT, ST: ok / warn / blocked */
    val services: List<String>?,
    val days: Int,
) {
    val stable: Boolean get() = days >= 3
    val reality: Boolean get() = raw.contains("security=reality")
    val displayName: String get() = urlDecode(name).ifBlank { "(без имени)" }

    val subtitle: String
        get() {
            val parts = ArrayList<String>()
            parts += protocol.uppercase() + if (reality) " Reality" else ""
            parts += cc ?: "??"
            parts += "$days дн."
            if (services != null) {
                parts += ServiceBlocks.LABELS.zip(services).joinToString(" ") { (l, s) ->
                    l + when (s) {
                        "ok" -> "✓"
                        "blocked" -> "✗"
                        else -> "~"
                    }
                }
            }
            return parts.joinToString(" · ")
        }
}

object ConfigRepository {
    private val suffixed = Regex(
        "^([^#]*)#(?:(.*) \\| )?\\[([A-Z]{2})] TG:(\\w+) QW:(\\w+) CL:(\\w+) YT:(\\w+) ST:(\\w+)$"
    )

    fun load(storage: Storage): List<ConfigRow> {
        val file = storage.latestEnriched() ?: return emptyList()
        val days = Tracker(storage).load()

        val rows = ArrayList<ConfigRow>()
        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val protocol = line.substringBefore("://", "")
            if (protocol.isEmpty()) return@forEachLine

            val m = suffixed.find(line)
            val row = if (m != null) {
                // у vmess часто нет #имени — берём ps из JSON, как и трекер
                val name = m.groupValues[2].ifEmpty { LinkParser.parse(m.groupValues[1])?.title.orEmpty() }
                ConfigRow(
                    raw = line,
                    protocol = protocol,
                    name = name,
                    cc = m.groupValues[3],
                    services = listOf(4, 5, 6, 7, 8).map { m.groupValues[it] },
                    days = days[name]?.days ?: 0,
                )
            } else {
                val name = line.substringAfter('#', "").ifEmpty { LinkParser.parse(line)?.title.orEmpty() }
                ConfigRow(line, protocol, name, null, null, days[name]?.days ?: 0)
            }
            rows += row
        }
        // стабильные выше; сортировка устойчивая, внутри группы порядок источников сохраняется
        return rows.sortedByDescending { it.days }
    }

    fun reportText(storage: Storage): String {
        val sb = StringBuilder()
        storage.latestReport()?.let { sb.append(LogMask.applyAll(it.readText())) }
        if (storage.logFile.exists()) {
            sb.append("\n=== ЖУРНАЛ (последние строки) ===\n")
            sb.append(LogTrim.tailLines(storage.logFile, 64 * 1024).takeLast(60).joinToString("\n"))
        }
        return sb.toString().ifBlank { "Пока ничего не запускалось. Нажмите «Собрать сейчас»." }
    }
}
