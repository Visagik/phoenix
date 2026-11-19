package com.phoenix.desktop

import com.phoenix.app.Json
import com.phoenix.app.LocalSocks
import com.phoenix.app.SocksAuth
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * Настоящий запуск Xray. Нужен путь к ядру в переменной PHOENIX_TEST_XRAY (после установки ядра в приложении это
 * ~/.local/share/phoenix/xray/xray); без неё тесты тихо проходят. Прокси-сервер не нужен: выход freedom, «сайт» — локальный
 * HTTP-сервер, так что проверяется вся цепочка процесс → SOCKS с паролем → ответ → задержка без выхода в интернет.
 */
class DesktopCoreE2ETest {
    private fun xray(): File? = System.getenv("PHOENIX_TEST_XRAY")?.let { File(it) }?.takeIf { it.isFile && it.canExecute() }

    private fun withSite(block: (Int) -> Unit) {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/ok") { ex -> ex.sendResponseHeaders(204, -1); ex.close() }
        srv.createContext("/page") { ex -> val b = "hello".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        srv.createContext("/nope") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
        srv.start()
        try {
            block(srv.address.port)
        } finally {
            srv.stop(0)
        }
    }

    private val freedomConfig = Json.write(
        linkedMapOf(
            "log" to mapOf("loglevel" to "none"),
            "outbounds" to listOf(mapOf("tag" to "proxy", "protocol" to "freedom")),
        )
    )

    @Test
    fun probeThroughRealXray() {
        val bin = xray() ?: return
        val pool = CorePool({ bin })
        val core = DesktopCore(pool)
        try {
            withSite { port ->
                val ms = core.probe(freedomConfig, "http://127.0.0.1:$port/ok")
                assertTrue("ожидалась задержка, получено $ms", ms in 0..5000)
                assertTrue(core.probe(freedomConfig, "http://127.0.0.1:$port/page") >= 0)
                // 403 — для probe это неудача (как у measureOutboundDelay)
                assertEquals(-1L, core.probe(freedomConfig, "http://127.0.0.1:$port/nope"))
            }
        } finally {
            pool.closeAll()
        }
    }

    @Test
    fun poolReusesAndClosesProcess() {
        val bin = xray() ?: return
        val pool = CorePool({ bin }, idleMs = 200)
        var first = 0
        var second = 0
        pool.use("k", { p, a -> ConfigInject.socksInbound(freedomConfig, p, a) }) { first = it.port }
        pool.use("k", { p, a -> ConfigInject.socksInbound(freedomConfig, p, a) }) { second = it.port }
        assertEquals(first, second)
        assertTrue(Ports.open(first))
        pool.closeAll()
        Thread.sleep(300)
        assertTrue(!Ports.open(first))
    }

    @Test
    fun clientProcessServesSocksAndHttp() {
        val bin = xray() ?: return
        val socks = Ports.free()
        val http = Ports.free()
        val health = Ports.free()
        val auth = SocksAuth.random()
        // ссылка на заведомо недоступный сервер: проверяем, что ядро принимает наш конфиг и поднимает входы (трафик не гоняем)
        val cfg = ClientConfig.build("vless://11111111-1111-1111-1111-111111111111@127.0.0.1:9?encryption=none&security=none&type=tcp#T", socks, http, health, auth)
        val proc = XrayProc(bin, cfg)
        try {
            assertTrue("ядро не запустилось: ${proc.output()}", proc.start(socks))
            assertTrue(Ports.open(http))
            assertTrue(Ports.open(health))
        } finally {
            proc.stop()
        }
        assertTrue(!Ports.open(socks))
        assertTrue(Paths.runDir.listFiles().orEmpty().none { it.name.startsWith("xray-") })
    }

    @Test
    fun localSocksWithPasswordReachesSite() {
        val bin = xray() ?: return
        val port = Ports.free()
        val auth = SocksAuth.random()
        val proc = XrayProc(bin, ConfigInject.socksInbound(freedomConfig, port, auth))
        try {
            assertTrue(proc.start(port))
            withSite { sitePort ->
                LocalSocks.get(port, auth, "http://127.0.0.1:$sitePort/page").use { r ->
                    assertEquals(200, r.code)
                    assertEquals("hello", r.head(100))
                }
            }
        } finally {
            proc.stop()
        }
    }
}
