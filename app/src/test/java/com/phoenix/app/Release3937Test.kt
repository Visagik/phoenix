package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3937Test {
    private val link = "vless://11111111-1111-1111-1111-111111111111@h.example.com:443?type=tcp&security=tls&sni=h.example.com#N"
    private val gemini = CheckService("GE", "Gemini", "https://gemini.google.com/")
    private val yt = CheckService("YT", "YouTube", "https://www.youtube.com/generate_204")

    @Test fun regionPageMarkers() {
        assertTrue(RegionBlock.blocked("https://gemini.google.com/app", "<h1>Gemini isn&#39;t currently supported in your country</h1>"))
        assertTrue(RegionBlock.blocked("https://x/", "Gemini isn\\u0027t supported in your country. Stay tuned"))
        assertTrue(RegionBlock.blocked("https://x/", "NotebookLM is not available in your country"))
        assertTrue(RegionBlock.blocked("https://x/", "Сервис пока недоступен в вашей стране"))
        assertTrue(RegionBlock.blocked("https://x/", "Gemini no está disponible en tu país"))
        assertTrue(RegionBlock.blocked("https://notebooklm.google.com/unavailable?x=1", ""))
        assertFalse(RegionBlock.blocked("https://gemini.google.com/app", "<html><title>Gemini</title>Chat with Gemini. Available in many countries</html>"))
        assertFalse(RegionBlock.blocked("", ""))
    }

    @Test fun rulesParseAndFallback() {
        assertNull(GeoRules.parse(""))
        assertNull(GeoRules.parse("closed: RU"))
        val r = GeoRules.parse("# свои\nhost: Example.AI\nclosed: ru, kz\nmarker: closed for (you|your)\npath: /nope\nmarker: (")!!
        assertEquals(listOf("example.ai"), r.hosts)
        assertEquals(setOf("RU", "KZ"), r.closed)
        assertEquals(1, r.markers.size) // неверное выражение пропущено
        assertEquals(listOf("/nope"), r.pathHints)
        assertTrue(RegionBlock.blocked("https://e/", "this is closed for you", r))
        // испорченный файл — действуют встроенные правила
        val f = File.createTempFile("geo", ".txt")
        f.writeText("мусор без структуры")
        assertFalse(GeoRules.reload(f))
        assertTrue(GeoGate.applies("https://gemini.google.com/"))
        f.writeText("host: only.example\nclosed: DE")
        assertTrue(GeoRules.reload(f))
        assertTrue(GeoGate.applies("https://only.example/"))
        assertTrue(GeoGate.blocks("https://only.example/", "de"))
        assertFalse(GeoGate.applies("https://gemini.google.com/"))
        f.delete()
        assertFalse(GeoRules.reload(f))
        assertTrue(GeoGate.applies("https://notebook.google/"))
    }

    @Test fun defaultGeoGate() {
        assertTrue(GeoGate.applies("https://notebook.google/"))
        assertTrue(GeoGate.blocks("https://claude.ai/", "HK"))
        assertFalse(GeoGate.blocks("https://gemini.google.com/", "HK"))
        assertTrue(GeoGate.blocks("https://gemini.google.com/", "RU"))
        assertFalse(GeoGate.blocks("https://example.org/", "RU"))
        assertEquals("NL", GeoGate.parseLoc("fl=1\nloc=NL\n"))
    }

    @Test fun serviceCheckerCatchesRegionPageWith200() {
        val probe = { _: String, _: String -> 40L }
        val plain = runBlocking { ServiceChecker(probe, parallel = 1).check(listOf(link), listOf(gemini, yt), "2026-10-29") }.values.first()
        assertTrue(plain.services["GE"]!! >= 0)
        val checker = ServiceChecker(probe, parallel = 1, exit = { "NL" }, region = { _, url -> url.contains("gemini") })
        val r = runBlocking { checker.check(listOf(link), listOf(gemini, yt), "2026-10-29") }.values.first()
        assertEquals(CheckServices.FAILED, r.services["GE"])
        assertTrue(r.services["YT"]!! >= 0) // YouTube не из списка регионов — страницу не разбираем
        // не удалось узнать (null) — не считаем закрытым
        val unknown = ServiceChecker(probe, parallel = 1, exit = { "NL" }, region = { _, _ -> null })
        assertTrue(runBlocking { unknown.check(listOf(link), listOf(gemini), "2026-10-29") }.values.first().services["GE"]!! >= 0)
    }

    @Test fun serviceCheckerHonoursVeto() {
        val probe = { _: String, _: String -> 40L }
        val checker = ServiceChecker(probe, parallel = 1, veto = { _, url -> url.contains("youtube") })
        val r = runBlocking { checker.check(listOf(link), listOf(gemini, yt), "2026-10-29") }.values.first()
        assertEquals(CheckServices.FAILED, r.services["YT"])
        assertTrue(r.services["GE"]!! >= 0)
    }

    @Test fun targetVerifierRejectsRegionPageAndVeto() {
        val cand = Candidate(link, ConfigIdentity.key(link), 1, 1.0, 10)
        val t = Target.app("com.google.android.apps.bard", "Gemini", "https://gemini.google.com/")
        val probe = { _: String, _: String -> 30L }
        val ok = runBlocking { TargetVerifier(probe, parallel = 1).verify(t, listOf(cand)) }
        assertEquals(1, ok.size)
        val blocked = TargetVerifier(probe, parallel = 1, region = { _, _ -> true })
        assertTrue(runBlocking { blocked.verify(t, listOf(cand)) }.isEmpty())
        assertTrue(blocked.lastReasons.keys.any { "недоступно" in it })
        val vetoed = TargetVerifier(probe, parallel = 1, veto = { _, _ -> true })
        assertTrue(runBlocking { vetoed.verify(t, listOf(cand)) }.isEmpty())
    }

    @Test fun serviceVetoStoreAndExpiry() {
        val f = File.createTempFile("veto", ".tsv").also { it.delete() }
        val v = ServiceVeto(f)
        assertFalse(v.active(link, "https://gemini.google.com/", 1000L))
        v.add(link, "https://gemini.google.com/app", 1000L)
        assertTrue(v.active(link, "https://gemini.google.com/", 2000L))
        assertTrue(v.active(link, "gemini.google.com", 2000L))
        assertFalse(v.active(link, "https://www.youtube.com/", 2000L))
        assertFalse(v.active("vless://other@x.example:1#X", "https://gemini.google.com/", 2000L))
        assertFalse(v.active(link, "https://gemini.google.com/", 1000L + ServiceVeto.TTL_MS + 1))
        f.delete()
    }

    @Test fun sourceDrift() {
        val prev = (1..100).map { "k$it" }.toSet()
        assertNull(SourceDrift.check(null, prev))
        assertNull(SourceDrift.check((1..5).toSet().map { "k$it" }.toSet(), setOf("a")))
        assertEquals(SourceDrift.Kind.SHRUNK, SourceDrift.check(prev, (1..30).map { "k$it" }.toSet())?.kind)
        assertEquals(SourceDrift.Kind.GREW, SourceDrift.check(prev, (1..350).map { "k$it" }.toSet())?.kind)
        assertEquals(SourceDrift.Kind.REPLACED, SourceDrift.check(prev, (500..600).map { "k$it" }.toSet())?.kind)
        assertNull(SourceDrift.check(prev, (10..120).map { "k$it" }.toSet()))
    }

    private fun cand(i: Int, proto: String) = Candidate(
        link = when (proto) {
            "reality" -> "vless://u$i@h$i.example:443?security=reality&type=tcp#R$i"
            "trojan" -> "trojan://p$i@h$i.example:443#T$i"
            else -> "ss://YWVzLTEyOC1nY206cGFzcw@h$i.example:443#S$i"
        },
        identity = "id$i", days = 1, mbps = 1.0, latencyMs = 1,
    )

    @Test fun protocolQuotaMixesHead() {
        val list = (1..30).map { cand(it, "reality") } + (31..40).map { cand(it, "trojan") } + (41..50).map { cand(it, "ss") }
        val out = ProtocolQuota.diversify(list)
        assertEquals(list.size, out.size)
        assertEquals(list.toSet(), out.toSet())
        assertEquals(list.take(4), out.take(4)) // начало не трогаем
        val head = out.take(24).map { ProtocolLearning.kindOf(it.link) }
        assertTrue(head.count { it == "reality" } <= 15)
        assertTrue(head.contains("trojan"))
        // один тип — порядок прежний
        val same = (1..30).map { cand(it, "reality") }
        assertEquals(same, ProtocolQuota.diversify(same))
        // короткий список не трогаем
        assertEquals(same.take(3), ProtocolQuota.diversify(same.take(3)))
    }

    @Test fun pageSnippet() {
        val html = "<html><head><title> Gemini  </title><style>a{}</style><script>var x=1;</script></head><body><h1>Not   supported</h1><p>in your country</p></body></html>"
        val t = PageSnippet.of(html)
        assertTrue(t.startsWith("«Gemini»"))
        assertTrue("Not supported in your country" in t)
        assertFalse("var x" in t || "a{}" in t)
        assertTrue(PageSnippet.of("x".repeat(1000)).length <= 240)
    }
}
