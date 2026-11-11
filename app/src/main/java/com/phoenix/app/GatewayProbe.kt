package com.phoenix.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Замер времени ответа шлюза (роутера) по быстрому соединению с ним: и «порт открыт», и «соединение отклонено» дают время ответа,
 * а таймаут значит, что роутер занят. Не требует прав; на мобильных сетях шлюз обычно молчит — тогда слежение само отключается ([GatewayLoad]).
 */
object GatewayProbe {
    private fun gateway(context: Context): Inet4Address? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.asSequence()
            .filter { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .mapNotNull { n -> cm.getLinkProperties(n)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway as? Inet4Address }
            .firstOrNull()
    } catch (_: Throwable) { null }

    /** Функция замера для [ServerSearch.loadSample] или null, если шлюз не определён. */
    fun sampler(context: Context): (() -> Long)? {
        val gw = gateway(context) ?: return null
        return {
            val t0 = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress(gw, 80), 800) }
                (System.nanoTime() - t0) / 1_000_000
            } catch (_: SocketTimeoutException) {
                -1L
            } catch (e: java.net.ConnectException) {
                if (e.message?.contains("refused", ignoreCase = true) == true) (System.nanoTime() - t0) / 1_000_000 else -1L
            } catch (_: Exception) {
                -1L
            }
        }
    }
}
