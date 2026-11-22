package com.phoenix.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Какая сеть у телефона на самом деле. Когда включён VPN, «активная» сеть — это сам VPN,
 * поэтому смотрим на настоящие сети под ним (без VPN-транспорта).
 */
object NetInfo {
    enum class Kind(val title: String) { WIFI("Wi-Fi"), ETHERNET("кабель"), MOBILE("мобильная сеть"), NONE("нет сети") }

    @Suppress("DEPRECATION")
    fun kind(context: Context): Kind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Kind.NONE
        var best = Kind.NONE
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val k = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.MOBILE
                else -> Kind.MOBILE
            }
            if (k.ordinal < best.ordinal || best == Kind.NONE) best = k
        }
        return best
    }

    /** Wi-Fi или кабель: можно без опаски качать данные для замеров скорости. */
    fun isUnmetered(context: Context): Boolean = kind(context).let { it == Kind.WIFI || it == Kind.ETHERNET }

    /** Короткое имя текущей сети для истории конфигов: «wifi», «mobile:мтс». Оператора система отдаёт без разрешений. */
    fun label(context: Context): String {
        val k = kind(context)
        val op = if (k == Kind.MOBILE) {
            try {
                context.getSystemService(android.telephony.TelephonyManager::class.java)?.networkOperatorName
            } catch (_: Throwable) {
                null
            }
        } else null
        return NetLabel.of(k.name, op)
    }

    /**
     * Отпечаток Wi-Fi (см. WifiId): читается из свойств сети, без разрешений; кеш на время жизни сети, чтобы не дёргать систему лишний раз
     * (вызывается при смене сети и при замерах, а не по таймеру).
     */
    @Volatile private var wifiCache: Triple<android.net.Network?, String, Long>? = null

    @Suppress("DEPRECATION")
    fun wifiId(context: Context): String {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
            val net = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.let { c ->
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            } ?: return ""
            val cached = wifiCache
            if (cached != null && cached.first == net && System.currentTimeMillis() - cached.third < 10 * 60_000L) return cached.second
            val lp = cm.getLinkProperties(net) ?: return ""
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
            val dns = lp.dnsServers.mapNotNull { it.hostAddress }
            val subnets = lp.linkAddresses.filter { it.address is java.net.Inet4Address }.map { WifiId.subnet(it.address.address, it.prefixLength) }
            val id = WifiId.of(gw, dns, lp.domains, subnets)
            wifiCache = Triple(net, id, System.currentTimeMillis())
            id
        } catch (_: Throwable) {
            ""
        }
    }

    /** Место сети для подсказки режима и для обнаружения перехода между двумя Wi-Fi: «wifi#a1b2c3», «mobile:мтс». */
    fun place(context: Context): String = WifiId.place(label(context), if (kind(context) == Kind.WIFI) wifiId(context) else "")
}

/** Опыт текущей сети для ранжирования конфигов режима. */
fun Storage.historyView(context: Context): (String) -> HistStat? = history().view(NetInfo.label(context))
