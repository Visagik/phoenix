package com.phoenix.app

/**
 * Почему система завершила процесс приложения (ApplicationExitInfo, Android 11+). Нужно для случаев «приложение закрылось само»:
 * падение, ANR, нехватка памяти или новый лимит памяти Android 17 («MemoryLimiter»).
 * Чистая логика: коды причин — числа из ApplicationExitInfo.REASON_*.
 */
object ExitReasons {
    fun reason(code: Int): String = when (code) {
        1 -> "приложение вышло само"
        2 -> "убито сигналом"
        3 -> "системе не хватило памяти"
        4 -> "ПАДЕНИЕ (необработанное исключение)"
        5 -> "ПАДЕНИЕ в нативном коде (ядро)"
        6 -> "ANR: приложение зависло"
        7 -> "ошибка запуска процесса"
        8 -> "изменено разрешение"
        9 -> "чрезмерное использование ресурсов"
        10 -> "закрыто пользователем"
        11 -> "остановлено пользователем (принудительно)"
        12 -> "завершился связанный процесс"
        13 -> "иная причина"
        14 -> "заморожено системой"
        15 -> "изменено состояние пакета"
        16 -> "приложение обновлено"
        else -> "код $code"
    }

    /** Завершения, о которых стоит сообщить в журнале (обычные выходы пользователя и обновления — нет). */
    fun notable(code: Int): Boolean = code in setOf(2, 3, 4, 5, 6, 7, 9, 12, 13, 14)

    /** Лимит памяти Android 17: описание содержит «MemoryLimiter». */
    fun memoryLimiter(description: String): Boolean = description.contains("MemoryLimiter", ignoreCase = true)

    fun line(code: Int, description: String, importance: Int, rssKb: Long): String {
        val tail = buildString {
            if (description.isNotBlank()) append(" · ").append(description.take(160))
            if (memoryLimiter(description)) append(" · ЛИМИТ ПАМЯТИ Android 17: приложению ограничили память")
            if (rssKb > 0) append(" · память ${rssKb / 1024} МБ")
            append(" · важность ").append(importance)
        }
        return "EXIT: прошлый процесс завершён: ${reason(code)}$tail"
    }

    /** Из трассы ANR: что делал главный поток («main» и первые кадры стека нашего пакета) — одной строкой. */
    fun anrSummary(trace: String, max: Int = 600): String {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return "трасса получена, главный поток не найден"
        val frames = lines.drop(start + 1).takeWhile { it.isNotBlank() }.filter { it.trim().startsWith("at ") }.map { it.trim().removePrefix("at ") }
        val ours = frames.filter { it.startsWith("com.phoenix") || it.startsWith("com.v2ray") }.take(4)
        val top = frames.take(3)
        return ("главный поток: " + top.joinToString(" ← ") + if (ours.isNotEmpty()) " | наш код: " + ours.joinToString(" ← ") else "").take(max)
    }
}
