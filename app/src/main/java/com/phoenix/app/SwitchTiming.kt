package com.phoenix.app

/** Строки журнала о времени смены сервера (У4): кто просил, сколько ждала очереди, подготовка и поиск отдельно. Нулевые и малые значения не пишутся. */
object SwitchTiming {
    const val MIN_WAIT_MS = 1_000L
    const val MIN_PREP_LINE_MS = 3_000L

    private fun sec(ms: Long) = ms / 1000

    /** «начинаю (просит: …, ждал очереди N с)»; без очереди — «начинаю (просит: …)». */
    fun startText(who: String, waitMs: Long): String {
        val w = if (waitMs >= MIN_WAIT_MS) ", ждал очереди ${sec(waitMs)} с" else ""
        return "начинаю (просит: ${who.ifBlank { "не указано" }}$w)"
    }

    /** Строка о подготовке (загрузка и рейтинг конфигов), только если она заметна (≥ 3 с); иначе null. */
    fun prepText(prepMs: Long, configs: Int): String? =
        if (prepMs >= MIN_PREP_LINE_MS) "подготовка ${sec(prepMs)} с (конфигов $configs)" else null

    /** «подготовка A с, поиск B с, всего C с»; части короче секунды пропускаются, «всего» пишется всегда. */
    fun text(prepMs: Long, searchMs: Long): String {
        val parts = ArrayList<String>()
        if (prepMs >= MIN_WAIT_MS) parts += "подготовка ${sec(prepMs)} с"
        if (searchMs >= MIN_WAIT_MS) parts += "поиск ${sec(searchMs)} с"
        parts += "всего ${sec(prepMs + searchMs)} с"
        return parts.joinToString(", ")
    }
}
