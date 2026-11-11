package com.phoenix.app

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Неверные часы телефона ломают TLS и Reality: сверяем со временем из заголовка Date любого HTTP-ответа. */
object ClockSkew {
    const val WARN_SECONDS = 120L

    /** Разница «телефон − сервер» в секундах или null, если заголовок не разобрать. */
    fun skewSeconds(dateHeader: String?, phoneNowMs: Long): Long? {
        if (dateHeader.isNullOrBlank()) return null
        return try {
            val server = ZonedDateTime.parse(dateHeader.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            Math.round((phoneNowMs - server) / 1000.0)
        } catch (_: Exception) {
            null
        }
    }

    fun bad(skewSeconds: Long): Boolean = Math.abs(skewSeconds) > WARN_SECONDS

    fun text(skewSeconds: Long): String {
        val a = Math.abs(skewSeconds)
        val human = when {
            a >= 86_400 -> "${a / 86_400} дн."
            a >= 3_600 -> "${a / 3_600} ч"
            a >= 60 -> "${a / 60} мин"
            else -> "$a с"
        }
        return (if (skewSeconds > 0) "спешат на " else "отстают на ") + human
    }
}

/** Вывод проверки утечки по IP: сравниваем адрес «мимо VPN» (приложение вне туннеля) и «через ядро» (как у остальных приложений). */
object LeakVerdict {
    enum class Kind { OK, SAME_IP, UNKNOWN }

    fun of(direct: String?, viaVpn: String?): Kind = when {
        direct.isNullOrBlank() || viaVpn.isNullOrBlank() -> Kind.UNKNOWN
        direct.trim() == viaVpn.trim() -> Kind.SAME_IP
        else -> Kind.OK
    }

    /** Берёт первый адрес (IPv4 или IPv6) из ответа вида «1.2.3.4» или «ip=…». */
    fun parseIp(body: String?): String? {
        val s = body?.trim().orEmpty()
        if (s.isEmpty()) return null
        val m = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-fA-F:]{2,39}:[0-9a-fA-F:]+)").find(s) ?: return null
        return m.value
    }

    fun text(kind: Kind, direct: String?, viaVpn: String?, country: String?): String = when (kind) {
        Kind.OK -> "✓ Через VPN виден другой адрес ($viaVpn${if (country != null) ", $country" else ""}), реальный ($direct) не светится"
        Kind.SAME_IP -> "✗ Адрес через VPN совпадает с реальным ($direct) — трафик идёт мимо сервера"
        Kind.UNKNOWN -> "не удалось проверить: ${if (direct.isNullOrBlank()) "нет прямого ответа" else "VPN не включён или не ответил"}"
    }
}
