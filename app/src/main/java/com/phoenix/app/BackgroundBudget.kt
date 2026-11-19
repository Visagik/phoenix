package com.phoenix.app

/** Лимит фонового трафика на мобильной сети: касается перепроверки, проверок сервисов и замеров скорости. Поиск сервера при обрыве не ограничивается никогда. */
object BackgroundBudget {
    const val DEFAULT_MB = 100
    /** 0 — без лимита. */
    val CHOICES = listOf(0, 50, 100, 300)

    fun clampMb(v: Int): Int = if (v in CHOICES) v else DEFAULT_MB

    /** Исчерпан ли суточный лимит: [usedBytes] — потрачено сегодня по мобильной сети, [limitMb] — лимит (0 — нет). */
    fun exhausted(usedBytes: Long, limitMb: Int): Boolean = limitMb > 0 && usedBytes >= limitMb * 1024L * 1024L
}

/** Сбор и перепроверка только по Wi-Fi: если удачного сбора не было больше трёх суток, ограничение временно снимается. */
object WifiOnlyPolicy {
    const val STALE_DAYS = 3

    /** Нужно ли сейчас требовать Wi-Fi. Ручной запуск и «никогда не собирали» Wi-Fi не требуют. */
    fun effective(wifiOnly: Boolean, manual: Boolean, lastSuccessAt: Long, now: Long): Boolean =
        wifiOnly && !manual && lastSuccessAt > 0 && now - lastSuccessAt < STALE_DAYS * 86_400_000L

    /** Ограничение включено, но из-за давнего сбора пропущено. */
    fun relaxed(wifiOnly: Boolean, manual: Boolean, lastSuccessAt: Long, now: Long): Boolean =
        wifiOnly && !manual && lastSuccessAt > 0 && now - lastSuccessAt >= STALE_DAYS * 86_400_000L
}
