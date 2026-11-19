package com.phoenix.app

/**
 * Имя сервера для показа на экране: без эмодзи, флагов и квадратных скобок, которые источники вставляют в названия
 * («Auto 5 → [🚀 Оптимальная]» → «Auto 5 → Оптимальная»). Хранимое имя и ссылка не меняются.
 */
object DisplayName {
    fun plain(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val drop = cp == '['.code || cp == ']'.code ||
                cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 ||
                type == Character.OTHER_SYMBOL.toInt() ||
                type == Character.NON_SPACING_MARK.toInt() && cp in 0xFE00..0xFE0F ||
                cp in 0x1F3FB..0x1F3FF
            if (!drop) sb.appendCodePoint(cp)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
