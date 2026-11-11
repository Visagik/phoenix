package com.phoenix.app

import java.util.Locale

/**
 * Переводы интерфейса. Исходный текст в коде русский и служит ключом: tr("Собрать сейчас").
 * Для строк с подстановками ключ содержит «{}»: tr("Серверов: {}", n). Нет перевода — показывается русский.
 * Словари лежат в assets/i18n/<язык>.tsv: «ключ ⇥ перевод» (перенос строки записан как \n, обратная косая как \\).
 * В переводе подстановки можно переставлять: {1}, {2}… (с единицы), либо оставить {} по порядку.
 */
object Tr {
    /** Языки, для которых есть словарь. Русский — исходный, словаря не требует. */
    val SUPPORTED = listOf("en", "uk", "tr", "es", "zh", "fa", "ar")
    val RTL = setOf("fa", "ar", "he", "ur")

    /** Читает словарь языка (в приложении — из assets); null — словаря нет. */
    @Volatile var loader: (String) -> String? = { null }
    /** Язык интерфейса; если не задан, берётся язык системы. */
    @Volatile var override: String? = null

    private var loadedLang: String? = null
    private var table: Map<String, String> = emptyMap()

    fun currentLang(): String {
        val l = (override ?: Locale.getDefault().language).lowercase()
        return when (l) {
            "iw" -> "he"
            "in" -> "id"
            else -> l
        }
    }

    fun isRtl(lang: String = currentLang()): Boolean = lang in RTL

    @Synchronized private fun ensure(lang: String) {
        if (lang == loadedLang) return
        table = if (lang == "ru" || lang !in SUPPORTED) emptyMap() else parse(loader(lang).orEmpty())
        loadedLang = lang
    }

    /** Принудительно перечитать словарь (после смены языка). */
    @Synchronized fun reset() { loadedLang = null }

    fun parse(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val v = unescape(line.substring(tab + 1))
            if (v.isNotBlank()) m[unescape(line.substring(0, tab))] = v
        }
        return m
    }

    fun escape(s: String): String = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    else -> { sb.append(c).append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** Подставляет аргументы: «{}» по порядку, «{n}» по номеру (с единицы). */
    fun fill(template: String, args: Array<out Any?>): String {
        if (args.isEmpty() || template.indexOf('{') < 0) return template
        val sb = StringBuilder()
        var next = 0
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val close = template.indexOf('}', i)
                if (close > i) {
                    val inner = template.substring(i + 1, close)
                    val idx = if (inner.isEmpty()) next++ else inner.toIntOrNull()?.minus(1)
                    if (idx != null && idx >= 0 && idx < args.size) {
                        sb.append(args[idx].toString()); i = close + 1; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    fun translate(key: String, args: Array<out Any?>): String {
        val lang = currentLang()
        ensure(lang)
        return fill(table[key] ?: key, args)
    }

    /** Сколько подстановок в шаблоне ({} и {n}): для проверки словарей. */
    fun placeholders(template: String): List<String> =
        Regex("\\{(\\d*)\\}").findAll(template).map { it.value }.toList()
}

/** Перевод строки интерфейса на язык системы. Подходит где угодно: и в Compose, и в уведомлениях. */
fun tr(key: String, vararg args: Any?): String = Tr.translate(key, args)
