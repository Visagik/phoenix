package com.phoenix.app

/**
 * Как итог пробы «живой» меняет запись проверки конфига (решение для SearchSink.flush вынесено сюда, чтобы его можно было проверить).
 */
object CheckUpdate {
    /**
     * @return новая запись или null, если менять нечего. Новый или мёртвый конфиг, либо живой за другой день — запись с нуля.
     * Живой в этот же день: обновляется только задержка и адрес, а сервисы и скорость, уже измеренные сегодня, остаются (Т3:
     * раньше задержка за день замерзала на первом замере — утром 300 мс, вечером 4000 мс, а в рейтинге оставалось 300).
     */
    fun forLive(prev: CheckResult?, ms: Long, date: String, host: String): CheckResult? = when {
        prev == null || prev.dead || prev.date != date -> CheckResult(date, ms, emptyMap(), host = host)
        // адрес сменился: сервисы и скорость старого адреса к новому не относятся (по host работает staleFor)
        prev.host.isNotEmpty() && host.isNotEmpty() && prev.host != host -> CheckResult(date, ms, emptyMap(), host = host)
        prev.baselineMs == ms && prev.host == host -> null
        else -> prev.copy(baselineMs = ms, host = host)
    }
}
