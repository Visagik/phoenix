package com.phoenix.app

import java.net.URLDecoder
import java.util.Base64

/**
 * Разобранная ссылка. Поддерживаются vless / vmess / trojan / ss и hysteria2 (hy2://, с ядром Xray 26+).
 * TUIC отбрасывается: в Xray его нет.
 */
data class ParsedLink(
    /** Ссылка без #фрагмента */
    val urlPart: String,
    /** Фрагмент после # как есть (URL-кодированный) */
    val name: String,
    val protocol: String,
    val host: String?,
    val port: Int?,
    val priority: Int,
    /** name, а если его нет — поле ps из vmess-JSON. Используется для определения страны. */
    val title: String,
) {
    val address: String
        get() = if (host != null && port != null) "$host:$port" else urlPart
}

object LinkParser {
    private val scheme = Regex("^(vless|vmess|trojan|ss|hysteria2|hy2)://")
    private val hostPort = Regex("@(\\[[0-9a-fA-F:.]+]|[^@/:?#\\s]+):(\\d{1,5})")

    /** Одну и ту же ссылку разбирают по 4–6 раз за проход рейтинга (host, address, key, staleFor): результат запоминается. Ссылка неизменяема, размер ограничен. */
    private const val CACHE_MAX = 16_384
    private val cache = BoundedCache<String, java.util.Optional<ParsedLink>>(CACHE_MAX)

    fun parse(line: String): ParsedLink? {
        cache.get(line)?.let { return it.orElse(null) }
        val r = parseUncached(line)
        cache.put(line, java.util.Optional.ofNullable(r))
        return r
    }

    internal fun parseUncached(line: String): ParsedLink? {
        val trimmed = line.trim()
        val m = scheme.find(trimmed) ?: return null
        val protocol = m.groupValues[1].let { if (it == "hy2") "hysteria2" else it }

        val hash = trimmed.indexOf('#')
        val urlPart = (if (hash >= 0) trimmed.substring(0, hash) else trimmed).trimEnd()
        val name = if (hash >= 0) trimmed.substring(hash + 1).trim() else ""

        var host: String? = null
        var port: Int? = null
        var psFromJson = ""

        if (protocol == "vmess") {
            val json = decodeBase64(urlPart.removePrefix("vmess://"))
            if (json != null) {
                host = jsonField(json, "add")?.takeIf { it.isNotBlank() }
                port = jsonField(json, "port")?.toIntOrNull()
                psFromJson = jsonField(json, "ps") ?: ""
            }
        } else {
            var hp = hostPort.find(urlPart)
            if (hp == null && protocol == "ss") {
                // ss://BASE64(method:pass@host:port)
                val body = urlPart.removePrefix("ss://").substringBefore('?')
                val decoded = decodeBase64(body)
                if (decoded != null) hp = hostPort.find(decoded)
            }
            if (hp != null) {
                host = hp.groupValues[1].trim('[', ']')
                port = hp.groupValues[2].toIntOrNull()
            }
        }

        return ParsedLink(
            urlPart = urlPart,
            name = name,
            protocol = protocol,
            host = host?.lowercase(),
            port = port,
            priority = priority(protocol, urlPart),
            title = if (name.isNotEmpty()) name else psFromJson,
        )
    }

    fun priority(protocol: String, url: String): Int {
        val reality = url.contains("security=reality")
        val vision = url.contains("flow=xtls-rprx-vision")
        return when {
            reality && vision -> 100
            reality -> 95
            vision -> 90
            url.contains("type=grpc") || url.contains("mode=grpc") -> 80
            url.contains("type=ws") && url.contains("security=tls") -> 70
            url.contains("type=tcp") && url.contains("security=tls") -> 65
            protocol == "hysteria2" -> 62
            protocol == "trojan" -> 60
            protocol == "vmess" -> 50
            protocol == "ss" -> 40
            else -> 30
        }
    }

    fun decodeBase64(s: String): String? {
        return try {
            val clean = s.trim().replace('-', '+').replace('_', '/').replace(Regex("\\s"), "")
            if (clean.isEmpty()) return null
            val noPad = clean.trimEnd('=')
            val padded = noPad + "=".repeat((4 - noPad.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun jsonField(json: String, key: String): String? {
        val m = Regex("\"" + key + "\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+))").find(json) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }
}

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** Как URLDecoder.decode(s, "UTF-8"), но без исключений: неполная «%» остаётся как есть («100% скорость» раньше не раскодировалось совсем). */
fun urlDecode(s: String): String {
    if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s
    val sb = StringBuilder(s.length)
    val bytes = java.io.ByteArrayOutputStream()
    fun flush() { if (bytes.size() > 0) { sb.append(bytes.toString("UTF-8")); bytes.reset() } }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val h = hexVal(s[i + 1]); val l = hexVal(s[i + 2])
            if (h >= 0 && l >= 0) { bytes.write(h * 16 + l); i += 3; continue }
        }
        flush()
        sb.append(if (c == '+') ' ' else c)
        i++
    }
    flush()
    return sb.toString()
}
