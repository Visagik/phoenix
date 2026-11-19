package com.phoenix.app

/**
 * Какой DNS использует туннель. [tun] — адреса, которые видят приложения (запросы на порт 53 перехватывает ядро), [plain] — запасные
 * обычные серверы ядра, [secure] — запросы по HTTPS/TCP через сервер (иначе UDP-DNS через многие серверы не проходит).
 * По умолчанию Cloudflare + Google: ровно то, что было до появления выбора.
 */
enum class DnsProvider(val title: String, val tun: List<String>, val plain: List<String>, val secure: List<String>) {
    CLOUDFLARE(
        "Cloudflare + Google (по умолчанию)",
        listOf("1.1.1.1", "8.8.8.8"), listOf("1.1.1.1", "8.8.8.8"),
        listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "tcp://1.1.1.1"),
    ),
    GOOGLE(
        "Google",
        listOf("8.8.8.8", "8.8.4.4"), listOf("8.8.8.8", "8.8.4.4"),
        listOf("https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query", "tcp://8.8.8.8"),
    ),
    QUAD9(
        "Quad9",
        listOf("9.9.9.9", "149.112.112.112"), listOf("9.9.9.9", "149.112.112.112"),
        listOf("https://9.9.9.9/dns-query", "https://149.112.112.112/dns-query", "tcp://9.9.9.9"),
    ),
    ADGUARD(
        "AdGuard (с блокировкой рекламы)",
        listOf("94.140.14.14", "94.140.15.15"), listOf("94.140.14.14", "94.140.15.15"),
        listOf("https://94.140.14.14/dns-query", "https://94.140.15.15/dns-query", "tcp://94.140.14.14"),
    );

    companion object {
        val DEFAULT = CLOUDFLARE

        /** Неизвестное или пустое значение (из копии другой версии) — умолчание, а не сбой. */
        fun fromId(id: String?): DnsProvider = entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

/** Проба нового DNS: даётся [TRIAL_SEC] секунд подтвердить, что всё работает, иначе возврат к прежнему. */
object DnsTrial {
    const val TRIAL_SEC = 30

    /** Сколько секунд осталось; 0 — время вышло или пробы нет. */
    fun remainingSec(startedAt: Long, now: Long): Int {
        if (startedAt <= 0L || now < startedAt) return 0
        return (TRIAL_SEC - (now - startedAt) / 1000).toInt().coerceIn(0, TRIAL_SEC)
    }

    /** Проба идёт, и время вышло. Часы ушли назад — тоже вышло (безопаснее вернуть, чем навсегда оставить непроверенный DNS). */
    fun expired(startedAt: Long, now: Long): Boolean {
        if (startedAt <= 0L) return false
        return now < startedAt || now - startedAt >= TRIAL_SEC * 1000L
    }
}
