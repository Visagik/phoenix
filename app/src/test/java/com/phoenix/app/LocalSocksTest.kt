package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalSocksTest {
    /** Игрушечный SOCKS5 с паролем: после рукопожатия отвечает на любой запрос заданным HTTP-ответом. */
    private fun server(user: String, pass: String, response: ByteArray, seenHost: MutableList<String>): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { return@thread }
                thread(isDaemon = true) { handle(c, user, pass, response, seenHost) }
            }
        }
        return ss
    }

    private fun handle(c: Socket, user: String, pass: String, response: ByteArray, seenHost: MutableList<String>) {
        c.use {
            val i = DataInputStream(it.getInputStream())
            val o = it.getOutputStream()
            i.readByte()
            val n = i.readByte().toInt()
            val methods = ByteArray(n); i.readFully(methods)
            if (2.toByte() !in methods) { o.write(byteArrayOf(5, -1)); return }
            o.write(byteArrayOf(5, 2))
            i.readByte()
            val u = ByteArray(i.readByte().toInt()); i.readFully(u)
            val p = ByteArray(i.readByte().toInt()); i.readFully(p)
            if (String(u) != user || String(p) != pass) { o.write(byteArrayOf(1, 1)); return }
            o.write(byteArrayOf(1, 0))
            i.readFully(ByteArray(3))
            val atyp = i.readByte().toInt()
            val host = when (atyp) {
                1 -> { val b = ByteArray(4); i.readFully(b); b.joinToString(".") { x -> (x.toInt() and 0xFF).toString() } }
                3 -> { val b = ByteArray(i.readByte().toInt()); i.readFully(b); String(b) }
                else -> { i.readFully(ByteArray(16)); "v6" }
            }
            i.readUnsignedShort()
            synchronized(seenHost) { seenHost += host }
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            // прочитать заголовки запроса
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0) break; sb.append(b.toChar()) }
            o.write(response); o.flush()
        }
    }

    private fun http(code: String, headers: String, body: String) = "HTTP/1.1 $code\r\n$headers\r\n\r\n$body".toByteArray()

    @Test
    fun getThroughPasswordProtectedSocks() {
        val seen = ArrayList<String>()
        val ss = server("u1", "p1", http("200 OK", "Content-Length: 5", "hello"), seen)
        try {
            LocalSocks.get(ss.localPort, SocksAuth("u1", "p1"), "http://example.test/path?q=1").use { r ->
                assertEquals(200, r.code)
                assertEquals("hello", r.head(100))
            }
            assertEquals(listOf("example.test"), seen)
        } finally { ss.close() }
    }

    @Test
    fun wrongPasswordIsRefused() {
        val ss = server("u1", "p1", http("200 OK", "", "x"), ArrayList())
        try {
            try {
                LocalSocks.get(ss.localPort, SocksAuth("u1", "bad"), "http://example.test/").close()
                throw AssertionError("ждали отказ")
            } catch (e: java.io.IOException) {
                assertTrue(e.message!!.contains("пароль"))
            }
        } finally { ss.close() }
    }

    @Test
    fun chunkedBodyAndRedirectAreHandled() {
        val seen = ArrayList<String>()
        val chunked = http("200 OK", "Transfer-Encoding: chunked", "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
        val ss = server("u", "p", chunked, seen)
        try {
            LocalSocks.get(ss.localPort, SocksAuth("u", "p"), "http://1.2.3.4/").use { r ->
                assertEquals("abcde", r.head(100))
            }
            assertEquals(listOf("1.2.3.4"), seen) // IPv4 уходит адресом, а не именем
        } finally { ss.close() }
        val seen2 = ArrayList<String>()
        val redir = http("302 Found", "Location: http://second.test/x", "")
        val ss2 = server("u", "p", redir, seen2)
        try {
            LocalSocks.get(ss2.localPort, SocksAuth("u", "p"), "http://first.test/", follow = true, maxRedirects = 1).use { r -> assertEquals(302, r.code) }
            assertEquals(listOf("first.test", "second.test"), seen2)
        } finally { ss2.close() }
    }

    @Test
    fun clientErrorBodyIsReadable() {
        val ss = server("u", "p", http("403 Forbidden", "Content-Length: 12", "Access denied"), ArrayList())
        try {
            LocalSocks.get(ss.localPort, SocksAuth("u", "p"), "http://x.test/").use { r ->
                assertEquals(403, r.code)
                assertTrue(r.head(4096).startsWith("Access"))
            }
        } finally { ss.close() }
    }

    @Test
    fun randomCredentialsDiffer() {
        val a = SocksAuth.random(); val b = SocksAuth.random()
        assertTrue(a.user != b.user && a.pass != b.pass && a.pass.length >= 24)
    }

    @Test
    fun xrayConfigRequiresPasswordWhenAuthGiven() {
        val link = "vless://11111111-1111-1111-1111-111111111111@s.example:443?security=reality&sni=a.com&pbk=AAAA&fp=chrome&type=tcp#Name"
        val auth = SocksAuth("user1", "pass1")
        val json = XrayConfig.socksConfig(link, 19000, emptyMap(), auth)
        assertTrue(json.contains("\"auth\":\"password\"") && json.contains("user1") && json.contains("pass1"))
        assertTrue(XrayConfig.socksConfig(link, 19000).contains("noauth"))
    }

    /** SOCKS с паролем, после рукопожатия поднимает TLS-сервер на том же соединении (как настоящий сайт за прокси). */
    private fun tlsServer(user: String, pass: String, ctx: javax.net.ssl.SSLContext, seenHost: MutableList<String>): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { return@thread }
                thread(isDaemon = true) {
                    try {
                        val i = DataInputStream(c.getInputStream()); val o = c.getOutputStream()
                        i.readByte(); val n = i.readByte().toInt(); i.readFully(ByteArray(n))
                        o.write(byteArrayOf(5, 2)); i.readByte()
                        val u = ByteArray(i.readByte().toInt()); i.readFully(u)
                        val p = ByteArray(i.readByte().toInt()); i.readFully(p)
                        if (String(u) != user || String(p) != pass) { o.write(byteArrayOf(1, 1)); c.close(); return@thread }
                        o.write(byteArrayOf(1, 0)); i.readFully(ByteArray(3))
                        val h = ByteArray(0).let { _ -> i.readByte().toInt(); val b = ByteArray(i.readByte().toInt()); i.readFully(b); String(b) }
                        i.readUnsignedShort(); synchronized(seenHost) { seenHost += h }
                        o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                        val tls = ctx.socketFactory.createSocket(c, null, c.port, true) as javax.net.ssl.SSLSocket
                        tls.useClientMode = false
                        tls.startHandshake()
                        val ti = tls.getInputStream(); val sb = StringBuilder()
                        while (!sb.endsWith("\r\n\r\n")) { val b = ti.read(); if (b < 0) break; sb.append(b.toChar()) }
                        tls.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecure".toByteArray()); tls.getOutputStream().flush()
                        tls.close()
                    } catch (_: Exception) { try { c.close() } catch (_: Exception) {} }
                }
            }
        }
        return ss
    }

    /** Самоподписанный сертификат для localhost (keytool) → SSLContext сервера и клиента. */
    private fun testContext(): javax.net.ssl.SSLContext {
        val dir = java.nio.file.Files.createTempDirectory("lstls").toFile()
        val ks = java.io.File(dir, "k.p12")
        val pr = ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "san=dns:localhost", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit")
            .redirectErrorStream(true).start()
        pr.inputStream.readBytes(); if (pr.waitFor() != 0) throw AssertionError("keytool")
        val store = java.security.KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509").apply { init(store, "changeit".toCharArray()) }
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance("SunX509").apply { init(store) }
        return javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
    }

    @Test
    fun httpsThroughPasswordProtectedSocks() {
        val ctx = testContext()
        val seen = ArrayList<String>()
        val ss = tlsServer("u", "p", ctx, seen)
        try {
            LocalSocks.get(ss.localPort, SocksAuth("u", "p"), "https://localhost:443/x", sslFactory = ctx.socketFactory).use { r ->
                assertEquals(200, r.code)
                assertEquals("secure", r.head(100))
            }
            assertEquals(listOf("localhost"), seen)
        } finally { ss.close() }
    }

    @Test
    fun httpsWithWrongHostnameInCertIsRejected() {
        val ctx = testContext()
        val ss = tlsServer("u", "p", ctx, ArrayList())
        try {
            try {
                LocalSocks.get(ss.localPort, SocksAuth("u", "p"), "https://other.test/", sslFactory = ctx.socketFactory).close()
                throw AssertionError("ждали отказ из-за имени в сертификате")
            } catch (e: java.io.IOException) { /* ожидаемо */ }
        } finally { ss.close() }
    }

    @Test
    fun statsLineCountsSuccessesAndErrorsByStage() {
        LocalSocks.statsLine() // обнулить
        val ss = server("u", "p", http("200 OK", "Content-Length: 1", "x"), ArrayList())
        try {
            LocalSocks.get(ss.localPort, SocksAuth("u", "p"), "http://a.test/").close()
            try { LocalSocks.get(ss.localPort, SocksAuth("u", "bad"), "http://a.test/").close() } catch (_: java.io.IOException) {}
            val line = LocalSocks.statsLine()!!
            assertTrue(line, line.contains("удачных 1") && line.contains("ошибок 1") && line.contains("SOCKS 1") && line.contains("неверный пароль"))
            assertEquals(null, LocalSocks.statsLine())
        } finally { ss.close() }
    }
}
