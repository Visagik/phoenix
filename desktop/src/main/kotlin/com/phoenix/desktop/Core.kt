package com.phoenix.desktop

import com.phoenix.app.Baseline
import com.phoenix.app.GeoGate
import com.phoenix.app.LocalSocks
import com.phoenix.app.RegionBlock
import com.phoenix.app.ServerHosts
import com.phoenix.app.Soft
import com.phoenix.app.SocksAuth
import com.phoenix.app.UnsupportedLinkException
import com.phoenix.app.XrayConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Запущенное ядро с локальным SOCKS-входом для проверок. */
class CoreSession(val proc: XrayProc, val port: Int, val auth: SocksAuth) {
    @Volatile var lastUse: Long = System.currentTimeMillis()
}

/**
 * Пул временных ядер для проверок. Один сервер проверяется десятками запросов (сервисы, регион, страна выхода):
 * ядро поднимается один раз и закрывается, когда им 20 секунд не пользовались.
 */
class CorePool(private val bin: () -> File?, private val idleMs: Long = 20_000) {
    private val sessions = ConcurrentHashMap<String, CoreSession>()
    private val locks = ConcurrentHashMap<String, Any>()

    @Volatile var lastError: String = ""
        private set

    init {
        val t = Thread {
            while (true) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = System.currentTimeMillis()
                for ((k, s) in sessions.entries.toList()) {
                    if (now - s.lastUse > idleMs && sessions.remove(k, s)) s.proc.stop()
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    /** Выполняет [block] через ядро с ключом [key]. null — ядра нет, конфиг не поддерживается или ядро не запустилось. */
    fun <T> use(key: String, build: (port: Int, auth: SocksAuth) -> String, block: (CoreSession) -> T): T? {
        val exe = bin() ?: run {
            lastError = "ядро Xray не установлено"
            return null
        }
        val session = synchronized(locks.getOrPut(key) { Any() }) {
            val alive = sessions[key]?.takeIf { it.proc.isRunning }
            alive ?: run {
                sessions.remove(key)?.proc?.stop()
                val port = Ports.free()
                val auth = SocksAuth.random()
                val config = try {
                    build(port, auth)
                } catch (_: UnsupportedLinkException) {
                    lastError = "конфиг не поддерживается"
                    return null
                }
                val proc = XrayProc(exe, config)
                if (!proc.start(port)) {
                    lastError = proc.output().ifBlank { "ядро не запустилось" }
                    return null
                }
                CoreSession(proc, port, auth).also { sessions[key] = it }
            }
        }
        session.lastUse = System.currentTimeMillis()
        return try {
            block(session)
        } finally {
            session.lastUse = System.currentTimeMillis()
        }
    }

    fun closeAll() {
        for ((k, s) in sessions.entries.toList()) if (sessions.remove(k, s)) s.proc.stop()
    }
}

/** То, что на Android делают libv2ray и SpeedRunner, — здесь через процесс Xray. */
class DesktopCore(private val pool: CorePool) {
    val lastError: String get() = pool.lastError

    /** Задержка через конфиг (в мс) или -1: успехом считается ответ 200/204, как у measureOutboundDelay в libv2ray. */
    fun probe(config: String, url: String): Long =
        if (url == Baseline.PRIMARY) Baseline.measure({ rawProbe(config, it) }, { pool.lastError }) else rawProbe(config, url)

    private fun rawProbe(config: String, url: String): Long = try {
        pool.use("cfg:$config", { p, a -> ConfigInject.socksInbound(config, p, a) }) { s ->
            val t0 = System.nanoTime()
            LocalSocks.get(s.port, s.auth, url, connectTimeoutMs = 6_000, readTimeoutMs = 6_000).use { r ->
                if (r.code == 200 || r.code == 204) (System.nanoTime() - t0) / 1_000_000 else -1L
            }
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun <T> viaLink(link: String, block: (CoreSession) -> T): T? = try {
        pool.use("link:$link", { p, a -> XrayConfig.socksConfig(link, p, ServerHosts.hostsForLink(link), a) }, block)
    } catch (_: Exception) {
        null
    }

    /** Мягкая проверка: любой HTTP-ответ значит, что сервер до сайта дотянулся. */
    fun reaches(link: String, url: String): Boolean = viaLink(link) { s ->
        LocalSocks.get(s.port, s.auth, url).use { r ->
            if (r.code in 400..499) Soft.accepts(r.code, r.head(4096)) else Soft.accepts(r.code)
        }
    } ?: false

    /** true — страница «в вашей стране не поддерживается»; false — обычная; null — узнать не удалось. */
    fun regionBlocked(link: String, url: String): Boolean? = viaLink(link) { s ->
        LocalSocks.get(
            s.port, s.auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000, follow = true,
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
        ).use { r -> RegionBlock.closed(r, r.head(65_536)) }
    }

    /** Страна выхода сервера (Cloudflare trace через него) или null. */
    fun exitCountry(link: String): String? = viaLink(link) { s ->
        val viaCloudflare = try {
            LocalSocks.get(s.port, s.auth, "https://www.cloudflare.com/cdn-cgi/trace").use { r ->
                if (r.code == 200) GeoGate.parseLoc(r.head(2048)) else null
            }
        } catch (_: Exception) {
            null
        }
        viaCloudflare ?: LocalSocks.get(s.port, s.auth, "https://api.country.is/").use { r ->
            if (r.code == 200) Regex("\"country\"\\s*:\\s*\"([A-Za-z]{2})\"").find(r.head(512))?.groupValues?.get(1)?.uppercase() else null
        }
    }

    fun close() = pool.closeAll()
}
