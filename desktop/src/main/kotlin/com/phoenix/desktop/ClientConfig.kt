package com.phoenix.desktop

import com.phoenix.app.Json
import com.phoenix.app.SocksAuth
import com.phoenix.app.XrayConfig

/** Конфиг Xray для обычного подключения на рабочем столе: SOCKS и HTTP на localhost + закрытый вход для самопроверки. */
object ClientConfig {
    private val PRIVATE_NETS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10",
    )

    fun build(
        link: String,
        socksPort: Int,
        httpPort: Int,
        healthPort: Int,
        healthAuth: SocksAuth,
        hosts: Map<String, String> = emptyMap(),
        bypassDomains: List<String> = emptyList(),
    ): String {
        val sniffing = mapOf("enabled" to true, "destOverride" to listOf("http", "tls"))
        val inbounds = listOf(
            mapOf(
                "tag" to "socks", "listen" to "127.0.0.1", "port" to socksPort, "protocol" to "socks",
                "settings" to mapOf("auth" to "noauth", "udp" to true), "sniffing" to sniffing,
            ),
            mapOf(
                "tag" to "http", "listen" to "127.0.0.1", "port" to httpPort, "protocol" to "http",
                "settings" to mapOf("allowTransparent" to false), "sniffing" to sniffing,
            ),
            mapOf(
                "tag" to "health", "listen" to "127.0.0.1", "port" to healthPort, "protocol" to "socks",
                "settings" to mapOf("auth" to "password", "accounts" to listOf(mapOf("user" to healthAuth.user, "pass" to healthAuth.pass)), "udp" to false),
            ),
        )
        val outbounds = listOf(
            XrayConfig.outbound(link),
            mapOf("tag" to "direct", "protocol" to "freedom"),
            mapOf("tag" to "block", "protocol" to "blackhole"),
        )
        val rules = ArrayList<Map<String, Any?>>()
        rules += mapOf("type" to "field", "inboundTag" to listOf("health"), "outboundTag" to XrayConfig.PROXY_TAG)
        rules += mapOf("type" to "field", "ip" to PRIVATE_NETS, "outboundTag" to "direct")
        if (bypassDomains.isNotEmpty()) {
            rules += mapOf("type" to "field", "domain" to bypassDomains.map { if (it.contains(':')) it else "domain:$it" }, "outboundTag" to "direct")
        }
        val config = linkedMapOf<String, Any?>(
            "log" to mapOf("loglevel" to "warning"),
            "dns" to mapOf("servers" to listOf("1.1.1.1", "8.8.8.8"), "hosts" to hosts.ifEmpty { null }),
            "inbounds" to inbounds,
            "outbounds" to outbounds,
            "routing" to mapOf("domainStrategy" to "AsIs", "rules" to rules),
        )
        return Json.write(config)
    }
}
