package com.phoenix.app

/**
 * Сайты выбранных для обхода приложений. Приложение в Android объявляет, какие ссылки оно открывает (домены «открывать в приложении»).
 * Эти же домены пускаем мимо VPN и в браузере: ссылка на сайт банка открывается так же, как само приложение банка. Без особых
 * разрешений: список доменов даёт система (Android 12+). Чистая логика — отбор и нормализация; чтение у системы — в [AppLinks].
 */
object AppLinkDomains {
    /** Такие домены в обход не попадают никогда: в России они режутся или нужны через VPN, а приложение может лишь «открывать» их ссылки. */
    private val DENY = listOf(
        "t.me", "telegram.org", "telegram.me", "telegra.ph", "youtube.com", "youtu.be", "googlevideo.com", "instagram.com", "facebook.com", "fb.com",
        "twitter.com", "x.com", "discord.com", "discord.gg", "openai.com", "chatgpt.com", "claude.ai", "anthropic.com", "linkedin.com", "tiktok.com",
        "google.com", "google.ru", "gstatic.com", "googleapis.com", "goo.gl", "play.google.com", "android.com", "whatsapp.com", "wa.me", "signal.org",
        "reddit.com", "medium.com", "spotify.com", "netflix.com", "twitch.tv", "soundcloud.com", "bing.com", "duckduckgo.com",
    )

    /** Предел числа правил: список приложений не должен раздувать конфиг. */
    const val MAX_DOMAINS = 400

    private fun denied(host: String): Boolean = DENY.any { host == it || host.endsWith(".$it") }

    /** Домены из объявлений приложений → правила Xray вида «domain:example.ru» без повторов и запрещённых. */
    fun rules(hosts: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in hosts) {
            val h = Domains.host(raw.trim().removePrefix("*.")) ?: continue
            if (h.startsWith("*") || denied(h)) continue
            if (h.all { it.isDigit() || it == '.' }) continue // IP-адрес, не домен
            out += Domains.rule(h)
            if (out.size >= MAX_DOMAINS) break
        }
        return out.toList()
    }
}
