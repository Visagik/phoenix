package com.v2ray.ang.service

/**
 * Привязка к нативной библиотеке hev-socks5-tunnel: она регистрирует методы под именем именно этого класса
 * (пакет и имя нельзя менять). Пользоваться через [com.phoenix.app.HevTunnel].
 */
object TProxyService {
    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStopService(): Boolean

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyGetStats(): LongArray?
}
