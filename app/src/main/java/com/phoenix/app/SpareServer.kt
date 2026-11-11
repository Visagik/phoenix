package com.phoenix.app

/**
 * Запасной сервер «в тени»: при удачном поиске второй найденный сервер запоминается. Когда текущий сервер падает в той же сети
 * и запасной ещё свежий, пробуется он, а полный поиск начинается только если и он не ответил.
 */
object SpareServer {
    const val MAX_AGE_MS = 30 * 60_000L
    private var link = ""
    private var identity = ""
    private var net = ""
    private var at = 0L

    @Synchronized
    fun remember(link: String, identity: String, net: String, now: Long) {
        this.link = link; this.identity = identity; this.net = net; this.at = now
    }

    /** Ссылка запасного, если он из этой же сети, свежий и не совпадает с [excludeIdentity]; иначе null. */
    @Synchronized
    fun take(net: String, now: Long, excludeIdentity: String): String? {
        if (link.isEmpty() || this.net != net || now - at > MAX_AGE_MS || now < at || identity == excludeIdentity) return null
        val l = link
        forget()
        return l
    }

    @Synchronized
    fun forget() { link = ""; identity = ""; net = ""; at = 0L }
}
