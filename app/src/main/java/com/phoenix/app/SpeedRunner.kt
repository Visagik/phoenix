package com.phoenix.app

import android.content.Context
import java.net.ServerSocket

/** Замер скорости через конфиг: ядро поднимает временный SOCKS на localhost, файл качается через него. */
object SpeedRunner {
    /** @return null, если ядра нет, конфиг не поддерживается или данные не пошли */
    fun run(context: Context, link: String, maxMs: Long = SpeedTest.MAX_MS, maxBytes: Long = SpeedTest.MAX_BYTES): SpeedResult? {
        if (!XrayCore.available) return null
        val port = freePort() ?: return null
        val auth = SocksAuth.random()
        val config = try {
            XrayConfig.socksConfig(link, port, ServerHosts.hostsForLink(link), auth)
        } catch (_: UnsupportedLinkException) {
            return null
        }
        val controller = try {
            XrayCore.init(context)
            XrayCore.Controller()
        } catch (e: Throwable) {
            return null
        }
        return try {
            controller.start(config, 0)
            if (!controller.isRunning) return null
            for (url in SpeedTest.URLS) {
                val result = try {
                    SpeedTest.measure({ open(url, port, auth) }, maxMs = maxMs, maxBytes = maxBytes)
                } catch (_: Exception) {
                    null
                }
                if (result != null) return result
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            controller.stop()
        }
    }

    /**
     * Мягкая проверка адреса через конфиг: любой HTTP-ответ (200, 403 от защиты сайта, 404…) значит, что сервер до сайта дотянулся.
     * Ядро (measureOutboundDelay) принимает только 200/204, поэтому Claude, ChatGPT и подобные ему «не открываются».
     */
    fun reaches(context: Context, link: String, url: String): Boolean {
        if (!XrayCore.available) return false
        val port = freePort() ?: return false
        val auth = SocksAuth.random()
        val config = try {
            XrayConfig.socksConfig(link, port, ServerHosts.hostsForLink(link), auth)
        } catch (_: UnsupportedLinkException) {
            return false
        }
        val controller = try {
            XrayCore.init(context)
            XrayCore.Controller()
        } catch (_: Throwable) {
            return false
        }
        return try {
            controller.start(config, 0)
            if (!controller.isRunning) return false
            LocalSocks.get(port, auth, url).use { r ->
                if (r.code in 400..499) Soft.accepts(r.code, r.head(4096)) else Soft.accepts(r.code)
            }
        } catch (_: Throwable) {
            false
        } finally {
            controller.stop()
        }
    }

    /**
     * Открывает страницу [url] через сервер (с переходами) и смотрит, не страница ли это «в вашей стране не поддерживается».
     * Код ответа тут ничего не говорит: заглушка приходит с 200. @return true — заглушка, false — обычная страница, null — не удалось узнать.
     */
    fun regionBlocked(context: Context, link: String, url: String): Boolean? {
        if (!XrayCore.available) return null
        val port = freePort() ?: return null
        val auth = SocksAuth.random()
        val config = try {
            XrayConfig.socksConfig(link, port, ServerHosts.hostsForLink(link), auth)
        } catch (_: UnsupportedLinkException) {
            return null
        }
        val controller = try {
            XrayCore.init(context)
            XrayCore.Controller()
        } catch (_: Throwable) {
            return null
        }
        return try {
            controller.start(config, 0)
            if (!controller.isRunning) return null
            LocalSocks.get(port, auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000, follow = true, userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").use { r ->
                RegionBlock.closed(r, r.head(65_536))
            }
        } catch (_: Throwable) {
            null
        } finally {
            controller.stop()
        }
    }

    /** Страна выхода сервера (Cloudflare trace через конфиг) или null, если узнать не удалось. */
    fun exitCountry(context: Context, link: String): String? {
        if (!XrayCore.available) return null
        val port = freePort() ?: return null
        val auth = SocksAuth.random()
        val config = try {
            XrayConfig.socksConfig(link, port, ServerHosts.hostsForLink(link), auth)
        } catch (_: UnsupportedLinkException) {
            return null
        }
        val controller = try {
            XrayCore.init(context)
            XrayCore.Controller()
        } catch (_: Throwable) {
            return null
        }
        return try {
            controller.start(config, 0)
            if (!controller.isRunning) return null
            val viaCloudflare = try {
                LocalSocks.get(port, auth, "https://www.cloudflare.com/cdn-cgi/trace").use { r ->
                    if (r.code == 200) GeoGate.parseLoc(r.head(2048)) else null
                }
            } catch (_: Throwable) {
                null
            }
            // запасной адрес: Cloudflare может быть закрыт отдельно (белые списки)
            viaCloudflare ?: LocalSocks.get(port, auth, "https://api.country.is/").use { r ->
                if (r.code == 200) Regex("\"country\"\\s*:\\s*\"([A-Za-z]{2})\"").find(r.head(512))?.groupValues?.get(1)?.uppercase() else null
            }
        } catch (_: Throwable) {
            null
        } finally {
            controller.stop()
        }
    }

    private fun open(url: String, port: Int, auth: SocksAuth): java.io.InputStream {
        val r = LocalSocks.get(port, auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 6_000, follow = true, userAgent = "Mozilla/5.0 (Phoenix speed test)")
        if (r.code !in 200..299) {
            r.close()
            throw java.io.IOException("HTTP ${r.code}")
        }
        return object : java.io.FilterInputStream(r.stream()) {
            override fun close() {
                r.close()
            }
        }
    }

    private fun freePort(): Int? = try {
        ServerSocket(0).use { it.localPort }
    } catch (_: Exception) {
        null
    }
}
