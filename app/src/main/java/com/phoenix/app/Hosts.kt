package com.phoenix.app

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Предварительная проверка сервера: находит его IP и пробует открыть TCP-соединение.
 * Мёртвые серверы так отсеиваются за пару секунд вместо десятков секунд ожидания внутри ядра,
 * а IP передаётся ядру в разделе dns.hosts (у Android у Go-ядра нет своего системного DNS).
 */
object ServerHosts {
    private class Entry(val ip: String?, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 5 * 60 * 1000L
    /** Неудача помнится недолго: сеть могла просто моргнуть. */
    private const val FAIL_TTL_MS = 60 * 1000L

    private val ipLiteral = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+$")

    fun isIp(host: String) = ipLiteral.matches(host)

    const val MAX_CACHE = 4096
    const val MAX_ADDRESSES = 3

    /** Сеть сменилась: найденные адреса могли устареть. */
    fun clear() { cache.clear() }

    internal fun cacheSize(): Int = cache.size

    /** Ограничивает размер: сначала просроченные, при переполнении — всё (кэш только ускоряет). */
    internal fun trim(now: Long) {
        cache.entries.removeIf { now - it.value.at >= (if (it.value.ip == null) FAIL_TTL_MS else TTL_MS) }
        if (cache.size > MAX_CACHE) cache.clear()
    }

    /**
     * Первый из адресов, к которому удалось подключиться (не больше [MAX_ADDRESSES]); общее время не больше [budgetMs]:
     * недоступный первый IP не должен приводить к отказу, если второй работает.
     */
    internal fun firstReachable(addrs: List<InetAddress>, budgetMs: Int, clock: () -> Long = System::currentTimeMillis, connect: (InetAddress, Int) -> Boolean): InetAddress? {
        val end = clock() + budgetMs
        for (a in addrs.take(MAX_ADDRESSES)) {
            val left = (end - clock()).toInt()
            if (left <= 0) break
            if (connect(a, left)) return a
        }
        return null
    }

    /**
     * @return IP сервера (для IP-адреса — он же), либо null, если сервер не отвечает на TCP.
     * Результат помнится 5 минут.
     */
    fun reach(host: String, port: Int, timeoutMs: Int = 3_000, now: () -> Long = System::currentTimeMillis): String? {
        val key = "$host:$port"
        val hit = cache[key]
        if (hit != null && now() - hit.at < (if (hit.ip == null) FAIL_TTL_MS else TTL_MS)) return hit.ip
        val ip = try {
            val addrs = if (isIp(host)) listOf(InetAddress.getByName(host)) else {
                val all = InetAddress.getAllByName(host).toList()
                val v4 = all.filter { it.address.size == 4 }
                if (v4.isNotEmpty()) v4 else listOf(InetAddress.getByName(host))
            }
            firstReachable(addrs, timeoutMs) { a, left -> try { Socket().use { it.connect(InetSocketAddress(a, port), left) }; true } catch (_: Exception) { false } }?.hostAddress
        } catch (_: Exception) {
            null
        }
        if (cache.size >= MAX_CACHE) trim(now())
        cache[key] = Entry(ip, now())
        return ip
    }

    /** UDP-серверы (hysteria2): TCP-проверка к ним бессмысленна, проверяем только что домен резолвится. */
    fun resolveOnly(host: String): String? = try {
        if (isIp(host)) host else (InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 } ?: InetAddress.getByName(host)).hostAddress
    } catch (_: Exception) { null }

    /** Домен → IP для раздела dns.hosts (для серверов, заданных IP-адресом, ничего не нужно). */
    fun hostsFor(host: String, ip: String): Map<String, String> =
        if (isIp(host) || host == ip) emptyMap() else mapOf(host to ip)

    /** То же для ссылки; при недоступности сервера пусто (ядро всё равно не подключится). */
    fun hostsForLink(link: String): Map<String, String> {
        val p = LinkParser.parse(link) ?: return emptyMap()
        val host = p.host ?: return emptyMap()
        val port = p.port ?: return emptyMap()
        val ip = (if (p.protocol == "hysteria2") resolveOnly(host) else reach(host, port)) ?: return emptyMap()
        return hostsFor(host, ip)
    }
}

/** Порт локального SOCKS ядра, пока VPN включён (0 — нет). Через него сбор конфигов ходит в интернет, когда напрямую закрыто. */
object LocalProxy {
    @Volatile var port: Int = 0
    @Volatile var auth: SocksAuth? = null
}
