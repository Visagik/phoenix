package com.phoenix.app

/**
 * Скрывает в строках журнала секреты из ссылок: длинные токены в пути (персональные ссылки подписок вроде your-durev.com/sub/<токен>/)
 * и значения параметров token/key/sub/uuid/pass. Журнал и отчёт пользователь отправляет другим людям, токен давал бы доступ к его подписке.
 */
object LogMask {
    private val URL = Regex("""https?://[^\s\]\)"'<>]+""")
    private val SECRET_KEYS = setOf("token", "key", "sub", "uuid", "id", "pass", "password", "secret", "auth", "access_token")
    private val TOKENISH = Regex("""[A-Za-z0-9_\-=.]{20,}""")

    /** «mail.example.ru» → «m***.ru»: видно, что за сайт, но не его адрес (журнал уходит другим людям). */
    fun host(host: String): String {
        val tld = host.substringAfterLast('.', "")
        return host.take(1) + "***" + if (tld.isNotEmpty() && tld != host) ".$tld" else ""
    }

    fun url(u: String): String {
        val qIdx = u.indexOf('?')
        val head = if (qIdx >= 0) u.substring(0, qIdx) else u
        val query = if (qIdx >= 0) u.substring(qIdx + 1) else ""
        val schemeEnd = head.indexOf("://") + 3
        val slash = head.indexOf('/', schemeEnd)
        val maskedHead = if (slash < 0) head else head.substring(0, slash) + head.substring(slash).split('/').joinToString("/") { seg ->
            if (seg.length >= 20 && TOKENISH.matches(seg)) seg.take(4) + "…" else seg
        }
        if (query.isEmpty()) return maskedHead
        val q = query.split('&').joinToString("&") { kv ->
            val i = kv.indexOf('=')
            if (i < 0) kv
            else {
                val k = kv.substring(0, i)
                val v = kv.substring(i + 1)
                if (k.lowercase() in SECRET_KEYS && v.length >= 6) "$k=${v.take(2)}…" else kv
            }
        }
        return "$maskedHead?$q"
    }

    /** Многострочный текст (отчёт): маскирует адреса в каждой строке, в том числе в старых, ещё не замаскированных отчётах. */
    fun applyAll(text: String): String = if (!text.contains("://")) text else URL.replace(text) { url(it.value) }

    fun apply(line: String): String = if (!line.contains("://")) line else URL.replace(line) { url(it.value) }
}
