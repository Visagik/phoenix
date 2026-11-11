package com.phoenix.app

/**
 * Базовая проверка «сервер вообще выводит в интернет». Ядро (measureOutboundDelay) считает успехом только 200/204,
 * поэтому, если один адрес отвечает «403 Forbidden» (его закрыл сам сервер или фронт перед ним), это ещё не значит,
 * что сервер мёртв: пробуем другие адреса проверки. Ошибка «closed pipe» — ядро ещё закрывало прежний экземпляр, пробуем снова.
 */
object Baseline {
    const val PRIMARY = "https://www.gstatic.com/generate_204"

    val ALTERNATIVES = listOf(
        "http://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204",
    )

    /** Сервер ответил (значит, он жив), но сам адрес проверки не принял: 403, 404, 5xx… */
    fun answered(error: String): Boolean = Regex("(?i)invalid status|status:? ?\\d{3}|\\b(403|404|429|5\\d\\d)\\b").containsMatchIn(error)

    fun transient(error: String): Boolean = error.contains("closed pipe", true) || error.contains("closed network", true)

    /**
     * @param probe задержка в мс либо отрицательное число
     * @param errorOf последняя ошибка ядра в этом потоке (после неудачного [probe])
     * @param pause пауза перед повтором (в тестах — пустая)
     */
    fun measure(
        probe: (String) -> Long,
        errorOf: () -> String,
        pause: (Long) -> Unit = { Thread.sleep(it) },
    ): Long {
        var ms = probe(PRIMARY)
        if (ms >= 0) return ms
        // «closed pipe»: ядро ещё закрывало прежний экземпляр (проверки разных задач пересеклись) — до трёх повторов с нарастающей паузой
        var retry = 0
        while (transient(errorOf()) && retry < 3) {
            pause(300L * ++retry)
            ms = probe(PRIMARY)
            if (ms >= 0) return ms
        }
        if (!answered(errorOf())) {
            // gstatic может быть закрыт отдельно от остального (белые списки, фильтр по SNI): один дешёвый запрос к Cloudflare по http
            // без TLS; таймаут/обрыв по обоим адресам — тогда сервер действительно не работает
            if (transient(errorOf())) return ms
            val alt = probe(ALTERNATIVES.first())
            return if (alt >= 0) alt else ms
        }
        for (alt in ALTERNATIVES) {
            ms = probe(alt)
            if (ms >= 0) return ms
            if (!answered(errorOf())) return ms // другая ошибка (обрыв, таймаут) — дальше пробовать смысла нет
        }
        return -1L
    }
}
