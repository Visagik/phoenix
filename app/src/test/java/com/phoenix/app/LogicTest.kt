package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Base64

class LogicTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    // ---------- парсер ----------

    @Test
    fun vlessRealityVision() {
        val p = LinkParser.parse("vless://uuid@1.2.3.4:443?security=reality&flow=xtls-rprx-vision#Germany")!!
        assertEquals("1.2.3.4", p.host)
        assertEquals(443, p.port)
        assertEquals(100, p.priority)
        assertEquals("Germany", p.name)
        assertEquals("vless://uuid@1.2.3.4:443?security=reality&flow=xtls-rprx-vision", p.urlPart)
    }

    @Test
    fun hysteriaAndTuicAreDropped() {
        assertNull(LinkParser.parse("tuic://x@1.1.1.1:443#a"))
        assertNull(LinkParser.parse("hysteria://x@1.1.1.1:443#a"))
    }

    @Test
    fun vmessBase64() {
        val json = """{"add":"Example.com","port":"8443","ps":"NL fast"}"""
        val p = LinkParser.parse("vmess://" + b64(json))!!
        assertEquals("example.com", p.host)
        assertEquals(8443, p.port)
        assertEquals("NL fast", p.title)
        assertEquals(50, p.priority)
    }

    @Test
    fun shadowsocksBase64Variants() {
        val a = LinkParser.parse("ss://" + b64("aes-256-gcm:pw") + "@5.6.7.8:8388#x")!!
        assertEquals("5.6.7.8", a.host)
        assertEquals(8388, a.port)

        val b = LinkParser.parse("ss://" + b64("aes-256-gcm:pw@9.9.9.9:1234") + "#y")!!
        assertEquals("9.9.9.9", b.host)
        assertEquals(1234, b.port)
    }

    @Test
    fun ipv6Host() {
        val p = LinkParser.parse("trojan://pw@[2001:db8::1]:443#v6")!!
        assertEquals("2001:db8::1", p.host)
        assertEquals(443, p.port)
    }

    // ---------- дедупликация ----------

    @Test
    fun exactDuplicatesKeepName() {
        val out = Dedup.clean(
            listOf(
                "vless://u@1.1.1.1:443?security=tls",
                "vless://u@1.1.1.1:443?security=tls#Named",
                "garbage line",
            )
        )
        assertEquals(1, out.size)
        assertEquals("Named", out[0].name)
    }

    @Test
    fun topThreePerAddressPrefersReality() {
        val lines = listOf(
            "ss://" + b64("m:p") + "@1.1.1.1:443#ss",
            "vmess://" + b64("""{"add":"1.1.1.1","port":"443","ps":"vm"}"""),
            "trojan://p@1.1.1.1:443#tr",
            "vless://u@1.1.1.1:443?security=reality&flow=xtls-rprx-vision#best",
            "vless://u@2.2.2.2:443?security=tls#other",
        )
        val top = Dedup.topPerAddress(Dedup.clean(lines), 3)
        val names = top.map { it.title }
        assertEquals(4, top.size)
        assertTrue("best" in names)
        assertFalse("ss" in names) // самый слабый на 1.1.1.1 отброшен
        assertTrue("other" in names)
    }

    // ---------- страны по именам ----------

    @Test
    fun countryFromBracketTextAndFlag() {
        assertEquals("NL", NameGeo.detect("[nl] fast"))
        assertEquals("DE", NameGeo.detect("Germany%20%7C%20x"))
        assertEquals("DE", NameGeo.detect("Берлин Германия"))
        assertEquals("IN", NameGeo.detect("%F0%9F%87%AE%F0%9F%87%B3%20node")) // флаг Индии
        assertNull(NameGeo.detect("just-a-name"))
    }

    @Test
    fun serviceBlocks() {
        assertEquals(listOf("blocked", "blocked", "blocked", "blocked", "blocked"), ServiceBlocks.status("RU"))
        assertEquals(listOf("ok", "warn", "blocked", "ok", "ok"), ServiceBlocks.status("TR"))
        assertEquals(List(5) { "ok" }, ServiceBlocks.status("DE"))
    }

    // ---------- жизненный цикл источника ----------

    @Test
    fun sourceLifecycle() {
        var st = SourceState()
        repeat(4) { st = SourcePolicy.onFailure(st, 0, "2026-10-01") }
        assertTrue(st.active)
        st = SourcePolicy.onFailure(st, 0, "2026-10-01")
        assertFalse(st.active) // 5 неудач подряд

        assertFalse(SourcePolicy.shouldReactivate(st, LocalDate.parse("2026-10-07")))
        assertTrue(SourcePolicy.shouldReactivate(st, LocalDate.parse("2026-10-08")))

        // реактивация #1: блокируется уже после 2 неудач
        st = st.copy(active = true, fails = 0, react = 1)
        st = SourcePolicy.onFailure(st, 404, "2026-10-08")
        assertTrue(st.active)
        st = SourcePolicy.onFailure(st, 404, "2026-10-08")
        assertFalse(st.active)

        // после 3 реактиваций — навсегда
        assertFalse(SourcePolicy.shouldReactivate(st.copy(react = 3), LocalDate.parse("2027-01-01")))

        // любой успех сбрасывает всё
        assertEquals(SourceState(true, 0, "2026-10-09", "OK", 0), SourcePolicy.onSuccess("2026-10-09"))
    }

    @Test
    fun sourceStateLineRoundTrip() {
        val st = SourceState(false, 5, "2026-10-01", "FAIL:404", 1)
        val (url, back) = SourceState.parse(st.toLine("https://x.y/a|b"))!!
        assertEquals("https://x.y/a|b", url)
        assertEquals(st, back)
    }
}
