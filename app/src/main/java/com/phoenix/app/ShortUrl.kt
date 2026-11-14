package com.phoenix.app

/** Короткая подпись адреса: без схемы и «www.», у GitHub-ссылок — «владелец/репозиторий › файл». */
object ShortUrl {
    fun of(url: String): String {
        val u = url.trim()
        val noScheme = u.substringAfter("://", u).removePrefix("www.")
        val host = noScheme.substringBefore('/').lowercase()
        val parts = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        return when {
            host == "raw.githubusercontent.com" && parts.size >= 4 ->
                "${parts[0]}/${parts[1]} › ${parts.last()}"
            host == "github.com" && parts.size >= 2 ->
                "${parts[0]}/${parts[1]}" + if (parts.size > 2) " › ${parts.last()}" else ""
            parts.isEmpty() -> host
            parts.size == 1 -> "$host/${parts[0]}"
            else -> "$host › ${parts.last()}"
        }
    }
}
