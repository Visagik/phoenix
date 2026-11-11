package com.phoenix.app

import android.content.Context
import com.v2ray.ang.service.TProxyService
import java.io.File

/** Обёртка над нативным hev-socks5-tunnel: любая ошибка загрузки или запуска = «недоступно», приложение остаётся на встроенном движке. */
object HevTunnel {
    @Volatile private var loaded: Boolean? = null

    val available: Boolean
        get() = loaded ?: synchronized(this) {
            loaded ?: try {
                System.loadLibrary("hev-socks5-tunnel")
                true
            } catch (_: Throwable) {
                false
            }.also { loaded = it }
        }

    fun start(context: Context, yaml: String, fd: Int): Boolean {
        if (!available) return false
        return try {
            val f = File(context.filesDir, "hev-socks5-tunnel.yaml")
            f.writeTextAtomic(yaml)
            TProxyService.TProxyStartService(f.absolutePath, fd)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isRunning(): Boolean = try { available && TProxyService.TProxyIsRunning() } catch (_: Throwable) { false }

    fun stop() {
        try { if (loaded == true) TProxyService.TProxyStopService() } catch (_: Throwable) { }
    }
}
