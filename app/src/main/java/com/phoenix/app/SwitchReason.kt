package com.phoenix.app

/** Человеческая причина смены сервера для уведомления и карточки: из технической строки проверки («gstatic.com: timeout; …»). */
object SwitchReason {
    fun describe(detail: String, forced: Boolean = false): String {
        if (forced) return "вы нажали «Сменить сервер»"
        val d = detail.lowercase()
        return when {
            d.isBlank() -> "нет ответа через туннель"
            "refused" in d -> "сервер отклонил соединение"
            "reset" in d -> "соединение сброшено"
            "timeout" in d || "timed out" in d -> "сервер не отвечает (таймаут)"
            "unknownhost" in d || "unresolved" in d -> "не находится адрес сервера"
            "ssl" in d || "handshake" in d || "certificate" in d -> "ошибка TLS при подключении"
            else -> Regex("http (\\d{3})").find(d)?.let { "сайты отвечают ошибкой HTTP ${it.groupValues[1]}" } ?: "нет связи через туннель"
        }
    }

    /** Подпись для уведомления: «нашёл рабочий сервер за 7 с (прежний: сервер не отвечает (таймаут))». */
    fun note(base: String, reason: String): String = if (reason.isBlank()) base else "$base — прежний: $reason"
}
