package com.phoenix.app

/** Российские сайты, которые по умолчанию идут напрямую, мимо VPN (правила Xray вида domain:…). */
object RussianSites {
    val DOMAINS: List<String> = listOf(
        // национальные зоны
        "domain:ru", "domain:xn--p1ai", "domain:su",
        // российские сервисы на «чужих» доменах
        "domain:vk.com", "domain:vk-cdn.net", "domain:userapi.com", "domain:vkuseraudio.net", "domain:vkuser.net",
        "domain:mycdn.me", "domain:yandex.com", "domain:yandex.net", "domain:yastatic.net", "domain:ya.cc",
        "domain:2gis.com", "domain:avito.st", "domain:wbstatic.net", "domain:sberbank.com",
    )
}

object Domains {
    private val hostRegex = Regex("^[a-z0-9\\u0400-\\u04ff]([a-z0-9\\u0400-\\u04ff.-]*[a-z0-9\\u0400-\\u04ff])?$")

    /** «https://www.Example.com/path» → «www.example.com»; null, если это не адрес сайта. */
    fun host(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.substringAfter("://", s).substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':').trim('.')
        if (s.startsWith("www.") && s.length > 4) s = s.removePrefix("www.")
        return if (s.contains('.') && hostRegex.matches(s)) s else null
    }

    /** Правило Xray для домена и всех его поддоменов. */
    fun rule(host: String): String = "domain:$host"
}
