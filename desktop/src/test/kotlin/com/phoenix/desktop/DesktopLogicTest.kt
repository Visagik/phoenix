package com.phoenix.desktop

import com.phoenix.app.Json
import com.phoenix.app.Mode
import com.phoenix.app.SocksAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopLogicTest {
    private val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443?encryption=none&security=tls&sni=example.com&type=tcp#T"

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("phx-desk").toFile()

    @Test
    fun dgstSha256IsParsedFromRealFormat() {
        val dgst = "MD5= 17349769d85d6d0c5043b25c30da9673\nSHA1= 4b9d50003e0ba1b67b375bfd0a51019c50a26f57\nSHA2-256= b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82\nSHA2-512= 9eb4e818\n"
        assertEquals("b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82", XrayInstaller.parseSha256(dgst))
        assertNull(XrayInstaller.parseSha256("MD5= 17349769d85d6d0c5043b25c30da9673"))
        assertNull(XrayInstaller.parseSha256(""))
    }

    @Test
    fun unpackTakesOnlyTopLevelWantedFiles() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in listOf("xray" to "BIN", "geoip.dat" to "G1", "geosite.dat" to "G2", "README.md" to "x", "../evil" to "e", "sub/xray" to "nested", "LICENSE" to "l")) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        val dir = tmp()
        val n = XrayInstaller.unpack(ByteArrayInputStream(bos.toByteArray()), dir)
        assertEquals(3, n)
        assertEquals("BIN", File(dir, "xray").readText())
        assertTrue(File(dir, "xray").canExecute())
        assertFalse(File(dir, "evil").exists())
        assertFalse(File(dir.parentFile, "evil").exists())
        assertFalse(File(dir, "README.md").exists())
        assertEquals(setOf("xray", "geoip.dat", "geosite.dat"), dir.list()!!.toSet())
    }

    @Test
    fun sha256OfFile() {
        val f = File(tmp(), "a.bin").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", XrayInstaller.sha256(f))
    }

    @Test
    fun socksInboundIsInjectedIntoProbeConfig() {
        val cfg = Json.write(linkedMapOf("log" to mapOf("loglevel" to "none"), "outbounds" to listOf(mapOf("tag" to "proxy", "protocol" to "freedom"))))
        val out = ConfigInject.socksInbound(cfg, 4321, SocksAuth("u1", "p1"))
        assertTrue(out.startsWith("{\"inbounds\":["))
        assertTrue(out.contains("\"port\":4321"))
        assertTrue(out.contains("\"user\":\"u1\""))
        assertTrue(out.contains("\"outbounds\""))
        assertEquals(1, Regex("\"inbounds\"").findAll(out).count())
        // уже есть входы — не трогаем
        assertEquals(out, ConfigInject.socksInbound(out, 1, SocksAuth("a", "b")))
        // пустой объект
        val inbound = Json.write(listOf(mapOf("tag" to "in", "listen" to "127.0.0.1", "port" to 5, "protocol" to "socks", "settings" to mapOf("auth" to "password", "accounts" to listOf(mapOf("user" to "a", "pass" to "b")), "udp" to false))))
        assertEquals("{\"inbounds\":$inbound}", ConfigInject.socksInbound("{}", 5, SocksAuth("a", "b")))
    }

    @Test
    fun clientConfigHasThreeInboundsAndRoutes() {
        val cfg = ClientConfig.build(link, 10808, 10809, 20000, SocksAuth("hu", "hp"), mapOf("example.com" to "93.184.216.34"), listOf("ru", "domain:example.org"))
        assertTrue(cfg.contains("\"port\":10808"))
        assertTrue(cfg.contains("\"port\":10809"))
        assertTrue(cfg.contains("\"port\":20000"))
        assertTrue(cfg.contains("\"protocol\":\"http\""))
        assertTrue(cfg.contains("\"inboundTag\":[\"health\"]"))
        assertTrue(cfg.contains("domain:ru"))
        assertTrue(cfg.contains("domain:example.org"))
        assertFalse(cfg.contains("domain:domain:"))
        assertTrue(cfg.contains("\"93.184.216.34\""))
        // первый outbound — прокси
        assertTrue(cfg.indexOf("\"tag\":\"proxy\"") < cfg.indexOf("\"tag\":\"direct\""))
    }

    @Test
    fun systemProxyUnquote() {
        assertEquals("manual", SystemProxy.unquote("'manual'"))
        assertEquals("none", SystemProxy.unquote(" 'none'\n"))
        assertEquals("8080", SystemProxy.unquote("8080"))
    }

    @Test
    fun settingsRoundTripAndDefaults() {
        val f = File(tmp(), "s.properties")
        val s = DesktopSettings(f)
        assertEquals(Mode.BLACKLIST, s.mode)
        assertEquals(6, s.intervalHours)
        assertEquals(10808, s.socksPort)
        assertTrue(s.systemProxy)
        s.mode = Mode.WHITELIST
        s.intervalHours = 999
        s.bypass = listOf("ru", "example.org")
        s.lang = "en"
        val again = DesktopSettings(f)
        assertEquals(Mode.WHITELIST, again.mode)
        assertEquals(72, again.intervalHours)
        assertEquals(listOf("ru", "example.org"), again.bypass)
        assertEquals("en", again.lang)
    }

    @Test
    fun portsFreeAndOpen() {
        val p = Ports.free()
        assertFalse(Ports.open(p))
        java.net.ServerSocket(p).use { assertTrue(Ports.open(p)) }
    }

    @Test
    fun missingBinaryGivesNullNotCrash() {
        val pool = CorePool({ null })
        val core = DesktopCore(pool)
        assertEquals(-1L, core.probe("{}", "http://127.0.0.1:1/"))
        assertNull(core.regionBlocked(link, "http://127.0.0.1:1/"))
        assertNull(core.exitCountry(link))
        assertFalse(core.reaches(link, "http://127.0.0.1:1/"))
        assertNotNull(pool.lastError)
    }
}

class DesktopAutostartTest {
    @Test
    fun entryQuotesPathsWithSpaces() {
        val e = Autostart.entry("/opt/phoenix/bin/phoenix")
        assertTrue(e.contains("Exec=/opt/phoenix/bin/phoenix --minimized"))
        assertTrue(e.startsWith("[Desktop Entry]"))
        assertEquals("\"/home/my user/p\"", Autostart.quote("/home/my user/p"))
        assertEquals("/usr/bin/p", Autostart.quote("/usr/bin/p"))
        assertEquals("\"/a\\\"b\"", Autostart.quote("/a\"b"))
    }
}
