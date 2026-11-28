package com.phoenix.app

/**
 * Эпизод потери связи через сервер: у него неизменное начало ([since]). Раньше начало хранил счётчик неудач, а каждая защита «сейчас не виню
 * сервер» обнуляла его (18 мест): после паузы начало сбоя переписывалось на более позднее, проверки редели («спокойно»), а при выключенном
 * экране мёртвый туннель не проверялся. Теперь защита счётчик сбрасывает, а эпизод остаётся открытым до успешной проверки или нового подключения.
 */
class OutageEpisode {
    @Volatile var since = 0L
        private set
    @Volatile private var lastFailAt = 0L

    val active: Boolean get() = since > 0L

    /** Неудачная проверка. true — это начало нового эпизода. Эпизод без неудач дольше [STALE_MS] считается завершившимся (страховка от «застрявшего»). */
    fun fail(now: Long): Boolean {
        val fresh = since == 0L || now - lastFailAt !in 0..STALE_MS
        if (fresh) since = now
        lastFailAt = now
        return fresh
    }

    /** Связь вернулась или подключились заново. */
    fun end() { since = 0L; lastFailAt = 0L }

    companion object {
        const val STALE_MS = 15 * 60_000L
    }
}
