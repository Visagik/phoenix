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
}

/** Опыт текущей сети для ранжирования конфигов режима. */
fun Storage.historyView(context: Context): (String) -> HistStat? = history().view(NetInfo.label(context))
