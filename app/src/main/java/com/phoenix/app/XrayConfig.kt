package com.phoenix.app

/** Кусок готового JSON, который вставляется как есть (например, поле extra у xhttp). */
class RawJson(val text: String)

/** Минимальный писатель JSON без зависимостей — чтобы генератор конфига проверялся обычными тестами. */
object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is RawJson -> sb.append(v.text)
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (value == null) continue // пустые поля в конфиг не пишем
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    append(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, item)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Грубая проверка, что строка — один JSON-объект со сбалансированными скобками. */
    fun looksLikeObject(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return false
        var depth = 0
        val stack = StringBuilder()
        var inString = false
        var escaped = false
        for ((i, c) in t.withIndex()) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> { depth++; stack.append(c) }
                '}', ']' -> {
                    depth--
                    if (depth < 0) return false
                    // закрывающая скобка должна соответствовать открывающей: {"a":[1}} — не JSON
                    val open = stack[stack.length - 1]
                    stack.setLength(stack.length - 1)
                    if ((c == '}' && open != '{') || (c == ']' && open != '[')) return false
                    if (depth == 0 && i != t.lastIndex) return false
                }
            }
        }
        return depth == 0 && !inString
    }
}

/** Ссылка не поддерживается или повреждена; message показывается пользователю. */
class UnsupportedLinkException(message: String) : Exception(message)

/** Что делать с IPv6: заблокировать (приложения уходят на IPv4), пустить через сервер или не трогать (утечка мимо VPN). */
enum class Ipv6Mode(val title: String) {
    BLOCK("блокировать (рекомендуется)"),
    TUNNEL("через сервер"),
    OFF("не трогать"),
}

data class XrayOptions(
    val tunName: String = "xray0",
    val mtu: Int = 1500,
    val dns: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val logLevel: String = "warning",
    /** Цели, которым нужен собственный сервер (приложения — по UID, сайты — по домену) */
    val routes: List<AppRoute> = emptyList(),
    /** Правила domain:… для сайтов, которые идут напрямую, мимо VPN */
    val bypassDomains: List<String> = emptyList(),
    /** Локальный SOCKS-вход для проверки самого туннеля (0 — не нужен) */
    val healthPort: Int = 0,
    /** Пароль на внутренний SOCKS-вход (null — без пароля, только для тестов). */
    val healthAuth: SocksAuth? = null,
    /** Домен сервера → IP, найденный приложением заранее (как «hosts» в v2rayNG): иначе ядру нечем его разрешить */
    val hosts: Map<String, String> = emptyMap(),
    val ipv6: Ipv6Mode = Ipv6Mode.BLOCK,
    /** Закрыть QUIC (UDP 443): приложения сразу переходят на TCP, а не ждут, пока сервер ответит по UDP. */
    val blockQuic: Boolean = false,
    /**
     * DNS-запросы приложений обрабатывает само ядро и спрашивает по HTTPS/TCP через сервер. Иначе запрос уходит как UDP-пакет,
     * а многие серверы UDP не пересылают: тогда имена не находятся, и работает только то, что ходит по IP (Telegram).
     */
    val secureDns: Boolean = true,
    /** >0: вместо TUN-входа ядра слушаем SOCKS на этом порту (с UDP) — туда трафик направляет hev-socks5-tunnel. */
    val tunSocksPort: Int = 0,
    val tunSocksAuth: SocksAuth? = null,
)

/**
 * Превращает ссылку (vless / vmess / trojan / ss) в конфиг Xray.
 * Образец полей — v2rayNG; здесь только то, что нужно для четырёх протоколов.
 */
object XrayConfig {
    const val PROXY_TAG = "proxy"
    const val DNS_TAG = "dns-out"
    val SECURE_DNS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "tcp://1.1.1.1")

    private val PRIVATE_NETS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10",
    )

    private val SS_METHODS = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "xchacha20-ietf-poly1305", "none", "plain",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    )

    /** Полный конфиг для VPN: TUN-вход (дескриптор отдаётся ядру отдельно), proxy / direct / block и маршруты целей. */
    fun vpnConfig(link: String, opts: XrayOptions = XrayOptions()): String {
        val sniffing = mapOf("enabled" to true, "destOverride" to listOf("http", "tls"))
        val inbounds = arrayListOf<Map<String, Any?>>(
            if (opts.tunSocksPort > 0) socksInbound("tun", opts.tunSocksPort, opts.tunSocksAuth, udp = true) + ("sniffing" to sniffing)
            else mapOf(
                "tag" to "tun",
                "protocol" to "tun",
                "settings" to mapOf("name" to opts.tunName, "MTU" to opts.mtu),
                "sniffing" to sniffing,
            )
        )
        if (opts.healthPort > 0) inbounds += socksInbound("health", opts.healthPort, opts.healthAuth)

        val outbounds = arrayListOf<Map<String, Any?>>()
        outbounds += outbound(link)
        outbounds += mapOf("tag" to "direct", "protocol" to "freedom")
        outbounds += mapOf("tag" to "block", "protocol" to "blackhole")
        if (opts.secureDns) outbounds += mapOf("tag" to DNS_TAG, "protocol" to "dns")

        // маршруты целей; ссылка, которую ядро не поддерживает, просто пропускается
        val usable = opts.routes.mapNotNull { r ->
            try {
                if (!r.direct) outbounds += outbound(r.link, r.tag)
                r
            } catch (_: UnsupportedLinkException) {
                null
            }
        }

        val rules = arrayListOf<Map<String, Any?>>()
        if (opts.healthPort > 0) rules += mapOf("type" to "field", "inboundTag" to listOf("health"), "outboundTag" to PROXY_TAG)
        if (opts.secureDns) rules += mapOf("type" to "field", "inboundTag" to listOf("tun"), "port" to "53", "outboundTag" to DNS_TAG)
        rules += mapOf("type" to "field", "ip" to PRIVATE_NETS, "outboundTag" to "direct")
        // IPv6 перехвачен туннелем, но пускать его некуда — закрываем, чтобы приложения сразу перешли на IPv4
        if (opts.ipv6 == Ipv6Mode.BLOCK) rules += mapOf("type" to "field", "ip" to listOf("::/0"), "outboundTag" to "block")
        if (opts.bypassDomains.isNotEmpty()) {
            rules += mapOf("type" to "field", "domain" to opts.bypassDomains, "outboundTag" to "direct")
        }
        if (opts.blockQuic) rules += mapOf("type" to "field", "network" to "udp", "port" to "443", "outboundTag" to "block")
        // сначала правила по приложению (точнее), потом по доменам: общие узлы Google одной цели не должны перехватывать приложение другой
        for (r in usable) {
            val out = if (r.direct) "direct" else r.tag
            if (r.uids.isNotEmpty()) rules += mapOf("type" to "field", "process" to r.uids, "outboundTag" to out)
        }
        for (r in usable) {
            val out = if (r.direct) "direct" else r.tag
            if (r.domains.isNotEmpty()) rules += mapOf("type" to "field", "domain" to r.domains, "outboundTag" to out)
        }

        val config = linkedMapOf<String, Any?>(
            "log" to mapOf("loglevel" to opts.logLevel),
            "dns" to dnsSection(
                if (opts.secureDns) SECURE_DNS + opts.dns else opts.dns, opts.hosts,
                if (opts.ipv6 == Ipv6Mode.TUNNEL) "UseIP" else "UseIPv4",
            ),
            "inbounds" to inbounds,
            "outbounds" to outbounds,
            "routing" to mapOf("domainStrategy" to "AsIs", "rules" to rules),
        )
        return Json.write(config)
    }

    /** Раздел dns: серверы и готовые адреса серверов-прокси (чтобы не искать их через сам туннель). */
    private fun dnsSection(servers: List<String>, hosts: Map<String, String>, strategy: String? = null): Map<String, Any?> =
        mapOf("servers" to servers, "hosts" to hosts.ifEmpty { null }, "queryStrategy" to strategy)

    private fun socksInbound(tag: String, port: Int, auth: SocksAuth?, udp: Boolean = false): Map<String, Any?> = mapOf(
        "tag" to tag,
        "listen" to "127.0.0.1",
        "port" to port,
        "protocol" to "socks",
        "settings" to if (auth == null) mapOf("auth" to "noauth", "udp" to udp)
        else mapOf("auth" to "password", "accounts" to listOf(mapOf("user" to auth.user, "pass" to auth.pass)), "udp" to udp),
    )

    /** Конфиг с локальным SOCKS-входом: через него замеряется скорость (трафик идёт через сервер из ссылки). */
    fun socksConfig(link: String, port: Int, hosts: Map<String, String> = emptyMap(), auth: SocksAuth? = null): String {
        val config = linkedMapOf<String, Any?>(
            "log" to mapOf("loglevel" to "none"),
            "dns" to dnsSection(XrayOptions().dns, hosts),
            "inbounds" to listOf(socksInbound("in", port, auth)),
            "outbounds" to outbounds(link),
        )
        return Json.write(config)
    }

    /** Лёгкий конфиг без входов и маршрутов — только для проверки доступности через ядро. */
    /** Можно ли построить конфиг из этой ссылки (протокол и транспорт поддерживаются). */
    fun supports(link: String): Boolean = try {
        probeConfig(link)
        true
    } catch (_: Exception) {
        false
    } catch (_: StackOverflowError) {
        // регулярные выражения разбора vmess на очень длинных значениях могут переполнить стек — это тоже «не поддерживается», а не падение (находка 1.5)
        false
    }

    fun probeConfig(link: String, hosts: Map<String, String> = emptyMap()): String {
        val config = linkedMapOf<String, Any?>(
            "log" to mapOf("loglevel" to "none"),
            "dns" to dnsSection(XrayOptions().dns, hosts),
            "outbounds" to outbounds(link),
        )
        return Json.write(config)
    }

    private fun outbounds(link: String): List<Map<String, Any?>> = listOf(
        outbound(link),
        mapOf("tag" to "direct", "protocol" to "freedom"),
        mapOf("tag" to "block", "protocol" to "blackhole"),
    )

    fun outbound(link: String, tag: String = PROXY_TAG): Map<String, Any?> {
        val map = LinkedHashMap(outboundFor(link))
        map["tag"] = tag
        return map
    }

    private fun outboundFor(link: String): Map<String, Any?> {
        val trimmed = link.trim().substringBefore('#')
        return when {
            trimmed.startsWith("vless://") -> vless(trimmed)
            trimmed.startsWith("vmess://") -> vmess(trimmed)
            trimmed.startsWith("trojan://") -> trojan(trimmed)
            trimmed.startsWith("ss://") -> shadowsocks(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> hysteria2(trimmed)
            else -> throw UnsupportedLinkException("Неизвестный протокол ссылки")
        }
    }

    // ------------------------------------------------------------------ разбор адреса

    private class Url(val user: String?, val host: String, val port: Int, val query: Map<String, String>)

    private val urlRegex = Regex("^[a-z0-9]+://(?:([^@/?#]*)@)?(\\[[^\\]]+]|[^:/?#]+):(\\d{1,5})(?:/[^?#]*)?(?:\\?([^#]*))?")

    private fun parseUrl(link: String): Url {
        val m = urlRegex.find(link) ?: throw UnsupportedLinkException("Не удалось разобрать адрес сервера")
        val port = m.groupValues[3].toInt()
        if (port !in 1..65535) throw UnsupportedLinkException("Некорректный порт: $port")
        return Url(
            user = m.groups[1]?.value?.let { percentDecode(it) },
            host = m.groupValues[2].trim('[', ']'),
            port = port,
            query = parseQuery(m.groupValues[4]),
        )
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            val value = if (eq >= 0) part.substring(eq + 1) else ""
            map[key] = percentDecode(value)
        }
        return map
    }

    /** Как URLDecoder, но '+' остаётся плюсом (в паролях и uuid он законный символ). */
    fun percentDecode(s: String): String = urlDecode(s.replace("+", "%2B"))

    // ------------------------------------------------------------------ протоколы

    private fun vless(link: String): Map<String, Any?> {
        val u = parseUrl(link)
        requireNotPrivate(u.host)
        val id = u.user?.takeIf { it.isNotBlank() } ?: throw UnsupportedLinkException("В ссылке нет UUID")
        val user = mapOf(
            "id" to id,
            "encryption" to (u.query["encryption"]?.takeIf { it.isNotBlank() } ?: "none"),
            "flow" to u.query["flow"]?.takeIf { it.isNotBlank() },
        )
        val streamSettings = stream(u.query, u.host, defaultSecurity = null)
        // Ядро запрещает vless без TLS/reality и без собственного шифрования на публичный IP-адрес
        val secured = (streamSettings["security"] as? String).let { it == "tls" || it == "reality" } || user["encryption"] != "none"
        if (!secured && isPublicIp(u.host)) throw UnsupportedLinkException("vless без TLS на публичный IP запрещён ядром")
        return mapOf(
            "tag" to PROXY_TAG,
            "protocol" to "vless",
            "settings" to mapOf("vnext" to listOf(mapOf("address" to u.host, "port" to u.port, "users" to listOf(user)))),
            "streamSettings" to streamSettings,
        )
    }

    /**
     * Hysteria2 (QUIC) встроенным исходящим `hysteria` ядра Xray 26+. Пароль лежит в streamSettings.hysteriaSettings.auth.
     * Обфускацию (salamander), порт-хоппинг и скорость не передаём: лучше отбросить такую ссылку, чем собрать неверный конфиг.
     */
    private fun hysteria2(link: String): Map<String, Any?> {
        val u = parseUrl(link)
        requireNotPrivate(u.host)
        val auth = u.user?.takeIf { it.isNotBlank() } ?: throw UnsupportedLinkException("В ссылке hysteria2 нет пароля")
        if (u.query["obfs"]?.isNotBlank() == true) throw UnsupportedLinkException("hysteria2 с obfs не поддерживается")
        val pin = (u.query["pinSHA256"] ?: u.query["pcs"])?.replace(":", "")?.lowercase()?.takeIf { it.isNotBlank() }
        val insecure = u.query["insecure"] == "1" || u.query["allowInsecure"] == "1"
        if (insecure && pin == null) throw UnsupportedLinkException("hysteria2 без проверки сертификата: новое ядро такой сервер не примет")
        val sni = u.query["sni"]?.takeIf { it.isNotBlank() } ?: u.host.takeIf { !ipRegex.matches(it) }
        if (sni == null && pin == null) throw UnsupportedLinkException("hysteria2 по IP без домена и отпечатка: сертификат не пройдёт проверку")
        return mapOf(
            "tag" to PROXY_TAG,
            "protocol" to "hysteria",
            "settings" to mapOf("version" to 2, "address" to u.host, "port" to u.port),
            "streamSettings" to mapOf(
                "network" to "hysteria",
                "security" to "tls",
                "tlsSettings" to mapOf(
                    "serverName" to sni,
                    "alpn" to listOf("h3"),
                    "pinnedPeerCertSha256" to pin,
                ),
                "hysteriaSettings" to mapOf("version" to 2, "auth" to auth, "udpIdleTimeout" to 60),
            ),
        )
    }

    private fun trojan(link: String): Map<String, Any?> {
        val u = parseUrl(link)
        requireNotPrivate(u.host)
        val password = u.user?.takeIf { it.isNotBlank() } ?: throw UnsupportedLinkException("В ссылке нет пароля")
        val server = mapOf(
            "address" to u.host,
            "port" to u.port,
            "password" to password,
            "flow" to u.query["flow"]?.takeIf { it.isNotBlank() },
        )
        return mapOf(
            "tag" to PROXY_TAG,
            "protocol" to "trojan",
            "settings" to mapOf("servers" to listOf(server)),
            "streamSettings" to stream(u.query, u.host, defaultSecurity = "tls"),
        )
    }

    private fun shadowsocks(link: String): Map<String, Any?> {
        val body = link.removePrefix("ss://")
        val host: String
        val port: Int
        val method: String
        val password: String
        val query: Map<String, String>

        if (body.substringBefore('?').contains('@')) {
            // SIP002: ss://BASE64(method:pass)@host:port  или  ss://method:pass@host:port
            val u = parseUrl(link)
            val user = u.user ?: throw UnsupportedLinkException("В ссылке нет метода и пароля")
            val creds = if (user.contains(':')) user else (LinkParser.decodeBase64(user) ?: user)
            val idx = creds.indexOf(':')
            if (idx <= 0) throw UnsupportedLinkException("Не удалось прочитать метод и пароль ss")
            method = creds.substring(0, idx)
            password = creds.substring(idx + 1)
            host = u.host
            port = u.port
            query = u.query
        } else {
            // старый вид: ss://BASE64(method:pass@host:port)
            val decoded = LinkParser.decodeBase64(body.substringBefore('?'))
                ?: throw UnsupportedLinkException("Не удалось прочитать ссылку ss")
            val at = decoded.lastIndexOf('@')
            val colon = decoded.indexOf(':')
            if (at <= 0 || colon <= 0 || colon > at) throw UnsupportedLinkException("Не удалось прочитать ссылку ss")
            method = decoded.substring(0, colon)
            password = decoded.substring(colon + 1, at)
            val hp = decoded.substring(at + 1)
            val pColon = hp.lastIndexOf(':')
            if (pColon <= 0) throw UnsupportedLinkException("В ссылке ss нет порта")
            host = hp.substring(0, pColon).trim('[', ']')
            port = hp.substring(pColon + 1).toIntOrNull() ?: throw UnsupportedLinkException("Некорректный порт ss")
            query = if (body.contains('?')) parseQuery(body.substringAfter('?')) else emptyMap()
        }

        requireNotPrivate(host)
        if (method.lowercase() !in SS_METHODS) throw UnsupportedLinkException("Метод шифрования ss не поддерживается ядром: $method")
        if (query["plugin"]?.isNotBlank() == true) throw UnsupportedLinkException("ss-плагины не поддерживаются")

        return mapOf(
            "tag" to PROXY_TAG,
            "protocol" to "shadowsocks",
            "settings" to mapOf(
                "servers" to listOf(mapOf("address" to host, "port" to port, "method" to method.lowercase(), "password" to password))
            ),
            "streamSettings" to mapOf("network" to "tcp"),
        )
    }

    private fun vmess(link: String): Map<String, Any?> {
        val json = LinkParser.decodeBase64(link.removePrefix("vmess://"))
            ?: throw UnsupportedLinkException("Не удалось раскодировать vmess")
        val f = flatJson(json)
        val host = f["add"]?.takeIf { it.isNotBlank() } ?: throw UnsupportedLinkException("В vmess нет адреса")
        val port = f["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw UnsupportedLinkException("В vmess некорректный порт")
        val id = f["id"]?.takeIf { it.isNotBlank() } ?: throw UnsupportedLinkException("В vmess нет id")
        requireNotPrivate(host)

        val net = f["net"]?.ifBlank { null } ?: "tcp"
        val q = LinkedHashMap<String, String>()
        q["type"] = net
        if (net == "tcp") f["type"]?.let { q["headerType"] = it }
        if (net == "grpc") {
            f["path"]?.let { q["serviceName"] = it }
            f["type"]?.let { q["mode"] = it }
        } else {
            f["path"]?.let { q["path"] = it }
        }
        f["host"]?.takeIf { it.isNotBlank() }?.let { q["host"] = it }
        if (f["tls"] == "tls") q["security"] = "tls"
        f["sni"]?.takeIf { it.isNotBlank() }?.let { q["sni"] = it }
        f["alpn"]?.takeIf { it.isNotBlank() }?.let { q["alpn"] = it }
        f["fp"]?.takeIf { it.isNotBlank() }?.let { q["fp"] = it }

        val user = mapOf(
            "id" to id,
            "alterId" to (f["aid"]?.toIntOrNull() ?: 0),
            "security" to (f["scy"]?.takeIf { it.isNotBlank() } ?: "auto"),
        )
        return mapOf(
            "tag" to PROXY_TAG,
            "protocol" to "vmess",
            "settings" to mapOf("vnext" to listOf(mapOf("address" to host, "port" to port, "users" to listOf(user)))),
            "streamSettings" to stream(q, host, defaultSecurity = null),
        )
    }

    // ------------------------------------------------------------------ транспорт и безопасность

    /**
     * Ядро ждёт ключ в base64url без «=» (символы «-» и «_»). В ссылках встречается обычный base64 («+», «/», «=»),
     * а «+» после разбора адреса иногда превращается в пробел: «invalid password» в журнале.
     */
    internal fun realityKey(raw: String): String = raw.trim().replace(' ', '+').replace('+', '-').replace('/', '_').trimEnd('=')

    /** Публичный IPv4/IPv6-адрес (не домен и не частная сеть). */
    internal fun isPublicIp(host: String): Boolean {
        val h = host.trim('[', ']')
        if (!ipRegex.matches(h)) return false
        if (h.contains(':')) {
            val l = h.lowercase()
            return !(l == "::1" || l.startsWith("fe80") || l.startsWith("fc") || l.startsWith("fd"))
        }
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return !(p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254))
    }

    private val ipRegex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+$")

    /** Адрес сервера — частная сеть или loopback (10.x, 192.168.x, 172.16–31.x, 169.254.x, 127.x, ::1, fe80/fc/fd), а не домен. */
    private fun isPrivateOrLoopbackIp(host: String): Boolean {
        val h = host.trim('[', ']')
        return ipRegex.matches(h) && !isPublicIp(h)
    }

    /**
     * Единственным правилом против частных сетей было «vless без TLS на публичный IP запрещён ядром» — а частные
     * сети как раз не проверялись вовсе (находка Фейбла №1.3). Публичный список может указывать на устройство
     * в домашней сети того, кто его публикует (роутер, принтер, NAS), а фоновая перепроверка серверов сама
     * открывает TCP-соединение и шлёт запрос по такому адресу. Запрещаем для всех протоколов, не только vless.
     */
    private fun requireNotPrivate(host: String) {
        if (isPrivateOrLoopbackIp(host)) throw UnsupportedLinkException("Адрес сервера указывает на частную сеть — ссылка не принимается")
    }

    private fun stream(q: Map<String, String>, server: String, defaultSecurity: String?): Map<String, Any?> {
        val type = (q["type"]?.takeIf { it.isNotBlank() } ?: "tcp").lowercase()
        val network = when (type) {
            "tcp", "raw" -> "tcp"
            "ws" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "h2", "http" -> "h2"
            else -> throw UnsupportedLinkException("Транспорт «$type» не поддерживается")
        }
        val host = q["host"]?.takeIf { it.isNotBlank() }
        val path = q["path"]?.takeIf { it.isNotBlank() }

        val out = linkedMapOf<String, Any?>("network" to network)
        var sniFromTransport: String? = host

        when (network) {
            "tcp" -> if (q["headerType"] == "http") {
                val hosts = host?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                out["tcpSettings"] = mapOf(
                    "header" to mapOf(
                        "type" to "http",
                        "request" to mapOf(
                            "version" to "1.1",
                            "method" to "GET",
                            "path" to listOf(path ?: "/"),
                            "headers" to mapOf(
                                "Host" to hosts,
                                "Connection" to listOf("keep-alive"),
                                "Pragma" to "no-cache",
                            ),
                        ),
                    )
                )
                sniFromTransport = hosts.firstOrNull()
            }
            "ws" -> out["wsSettings"] = mapOf(
                "path" to (path ?: "/"),
                "host" to host,
                "headers" to host?.let { mapOf("Host" to it) },
            )
            "httpupgrade" -> out["httpupgradeSettings"] = mapOf("path" to (path ?: "/"), "host" to host)
            "xhttp" -> {
                val extra = q["extra"]?.takeIf { it.isNotBlank() && Json.looksLikeObject(it) }
                out["xhttpSettings"] = mapOf(
                    "path" to (path ?: "/"),
                    "host" to host,
                    "mode" to q["mode"]?.takeIf { it.isNotBlank() },
                    "extra" to extra?.let { RawJson(it.trim()) },
                )
            }
            "h2" -> {
                val hosts = host?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                out["httpSettings"] = mapOf("path" to (path ?: "/"), "host" to hosts.ifEmpty { null })
                sniFromTransport = hosts.firstOrNull()
            }
            "grpc" -> out["grpcSettings"] = mapOf(
                "serviceName" to (q["serviceName"] ?: ""),
                "multiMode" to (q["mode"] == "multi"),
                "authority" to q["authority"]?.takeIf { it.isNotBlank() },
                "idle_timeout" to 60,
                "health_check_timeout" to 20,
            )
        }

        val security = (q["security"]?.takeIf { it.isNotBlank() } ?: defaultSecurity)?.lowercase()
        val serverName = q["sni"]?.takeIf { it.isNotBlank() }
            ?: sniFromTransport
            ?: server.takeIf { !ipRegex.matches(it) }

        when (security) {
            "tls" -> {
                out["security"] = "tls"
                // Ядро 26+ убрало allowInsecure: сертификат проверяется всегда. Для сервера без домена («по IP») и без
                // закреплённого отпечатка такая проверка не пройдёт никогда — конфиг пропускаем сразу, а не тратим на него время.
                val insecure = q["allowInsecure"] == "1" || q["insecure"] == "1" || q["allowInsecure"] == "true"
                val pin = (q["pcs"] ?: q["pinnedPeerCertSha256"])?.takeIf { it.isNotBlank() }
                if (insecure && pin == null && serverName == null) {
                    throw UnsupportedLinkException("allowInsecure без домена и отпечатка: новое ядро такой сертификат не примет")
                }
                out["tlsSettings"] = mapOf(
                    "serverName" to serverName,
                    "fingerprint" to q["fp"]?.takeIf { it.isNotBlank() },
                    "alpn" to q["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null },
                    "pinnedPeerCertSha256" to pin,
                    "verifyPeerCertByName" to (q["vcn"] ?: q["verifyPeerCertByName"])?.takeIf { it.isNotBlank() },
                )
            }
            "reality" -> {
                val pbk = q["pbk"]?.takeIf { it.isNotBlank() }?.let { realityKey(it) } ?: throw UnsupportedLinkException("У reality нет publicKey (pbk)")
                out["security"] = "reality"
                out["realitySettings"] = mapOf(
                    "serverName" to serverName,
                    "fingerprint" to (q["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"),
                    "publicKey" to pbk,
                    "shortId" to (q["sid"] ?: ""),
                    "spiderX" to q["spx"]?.takeIf { it.isNotBlank() },
                )
            }
            null, "none" -> {}
            else -> throw UnsupportedLinkException("Безопасность «$security» не поддерживается")
        }
        return out
    }

    // ------------------------------------------------------------------ плоский JSON (vmess)

    private val flatField = Regex("\"(\\w+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+(?:\\.\\d+)?)|(true|false|null))")

    internal fun flatJson(json: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in flatField.findAll(json)) {
            val key = m.groupValues[1]
            val value = when {
                m.groups[2] != null -> unescape(m.groupValues[2])
                m.groups[3] != null -> m.groupValues[3]
                else -> m.groupValues[4]
            }
            map.putIfAbsent(key, value)
        }
        return map
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            sb.append(code.toChar()); i += 4
                        } else sb.append('u')
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
