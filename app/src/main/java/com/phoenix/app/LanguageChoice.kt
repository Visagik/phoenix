package com.phoenix.app

/** Выбор языка интерфейса: пустой код — как в системе. Названия на своих языках, чтобы их можно было найти при чужом интерфейсе. */
object LanguageChoice {
    val items: List<Pair<String, String>> = listOf(
        "ru" to "Русский", "en" to "English", "uk" to "Українська", "tr" to "Türkçe",
        "es" to "Español", "zh" to "中文", "ar" to "العربية", "fa" to "فارسی",
    )

    fun clamp(code: String?): String = if (code != null && items.any { it.first == code }) code else ""

    /** Название выбранного языка; для пустого кода — null (показывается «Как в системе»). */
    fun nameOf(code: String?): String? = items.firstOrNull { it.first == clamp(code) }?.second
}
