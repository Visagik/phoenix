package com.phoenix.app

/**
 * Что считать «тем же самым конфигом». У многих источников адрес сервера меняется, а имя остаётся,
 * поэтому личность конфига — протокол + нормализованное имя. История, скорость, проверки и запасные
 * серверы привязываются к ней и переживают смену адреса. Если имени нет или оно слишком короткое
 * («a», «1»), личностью остаётся адрес сервера.
 */
object ConfigIdentity {
    private const val MIN_NAME = 4

    /** Метка, которую Phoenix сам дописывает к имени: «| [DE] TG:ok QW:ok …». Она не часть имени. */
    private val geoSuffix = Regex("\\s*\\|?\\s*\\[[A-Z]{2}]\\s+TG:\\S+.*$")

    /** Имя без флагов, эмодзи, знаков и регистра: «🇩🇪 Germany #3 | [DE] TG:ok…» → «germany 3». */
    fun normalizeName(raw: String): String {
        val s = geoSuffix.replace(urlDecode(raw), "")
        val sb = StringBuilder()
        var lastSpace = true
        for (c in s) {
            when {
                c.isLetterOrDigit() -> {
                    sb.append(c.lowercaseChar()); lastSpace = false
                }
                // пробелы и разделители схлопываем; эмодзи (суррогатные пары) и прочие символы выбрасываем
                c.isWhitespace() || c == '-' || c == '_' || c == '.' || c == '|' || c == '/' -> {
                    if (!lastSpace) sb.append(' '); lastSpace = true
                }
            }
        }
        return sb.toString().trim()
    }

    fun key(link: String): String {
        val p = LinkParser.parse(link) ?: return "raw|" + link.trim().substringBefore('#')
        val name = normalizeName(p.title)
        return if (name.length >= MIN_NAME) "${p.protocol}|$name" else "addr|${p.protocol}|${p.address}"
    }

    /** Только хост сервера (без порта): у конфигов с разными протоколами на одном сервере он общий. */
    fun host(link: String): String = LinkParser.parse(link)?.host.orEmpty()

    /** Адрес сервера, как он указан в ссылке (host:port); пусто, если разобрать не удалось. */
    fun address(link: String): String = LinkParser.parse(link)?.takeIf { it.host != null }?.address ?: ""
}
