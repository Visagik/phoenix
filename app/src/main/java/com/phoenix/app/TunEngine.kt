package com.phoenix.app

/** Чем гоним трафик из туннеля Android в ядро. Чистая логика: выбор движка и конфиг для hev-socks5-tunnel. */
object TunEngine {
    enum class Kind(val title: String) { BUILTIN("встроенный в ядро"), HEV("hev-socks5-tunnel") }

    class Choice(val kind: Kind, val why: String)

    /**
     * hev включается только когда: выбран в настройках, библиотека загрузилась и нет маршрутов по приложениям
     * (при hev ядро не видит, какое приложение открыло соединение — все соединения приходят с локального порта).
     */
    fun choose(wanted: Boolean, libAvailable: Boolean, hasUidRoutes: Boolean): Choice = when {
        !wanted -> Choice(Kind.BUILTIN, "выбран встроенный")
        !libAvailable -> Choice(Kind.BUILTIN, "библиотека hev недоступна на этом телефоне")
        hasUidRoutes -> Choice(Kind.BUILTIN, "есть маршруты по приложениям, их умеет только встроенный")
        else -> Choice(Kind.HEV, "выбран в настройках")
    }

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    /** Конфиг hev (YAML), как у v2rayNG: адрес клиента совпадает с адресом, выданным VpnService. */
    fun hevYaml(
        mtu: Int, ipv4: String, ipv6: String?, port: Int, user: String?, pass: String?,
        tcpTimeoutSec: Int = 300, udpTimeoutSec: Int = 60, logLevel: String = "warn",
    ): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $mtu")
        appendLine("  ipv4: $ipv4")
        if (ipv6 != null) appendLine("  ipv6: ${q(ipv6)}")
        appendLine("socks5:")
        appendLine("  port: $port")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        if (user != null && pass != null) {
            appendLine("  username: ${q(user)}")
            appendLine("  password: ${q(pass)}")
        }
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: ${tcpTimeoutSec * 1000}")
        appendLine("  udp-read-write-timeout: ${udpTimeoutSec * 1000}")
        appendLine("  log-level: $logLevel")
    }
}
