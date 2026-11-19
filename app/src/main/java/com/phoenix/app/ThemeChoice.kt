package com.phoenix.app

/** Выбор темы и размера журнала: числа хранятся в настройках, здесь их смысл. */
object ThemeChoice {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    fun clamp(v: Int): Int = if (v in SYSTEM..DARK) v else SYSTEM

    /** Тёмная ли тема при выбранном режиме [mode] и системной теме [system]. */
    fun dark(mode: Int, system: Boolean): Boolean = when (clamp(mode)) {
        LIGHT -> false
        DARK -> true
        else -> system
    }
}

/** Размер журнала: короткий 1 МБ, обычный 5 МБ, подробный 20 МБ. Читается всегда не больше 5 МБ с конца файла (память). */
object LogSize {
    const val SHORT = 0
    const val NORMAL = 1
    const val DETAILED = 2

    fun clamp(v: Int): Int = if (v in SHORT..DETAILED) v else NORMAL

    fun maxBytes(level: Int): Long = when (clamp(level)) {
        SHORT -> 1L * 1024 * 1024
        DETAILED -> 20L * 1024 * 1024
        else -> 5L * 1024 * 1024
    }

    /** После обрезки остаётся четыре пятых. */
    fun keepBytes(level: Int): Long = maxBytes(level) / 5 * 4
}
