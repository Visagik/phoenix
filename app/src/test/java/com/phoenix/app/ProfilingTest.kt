package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class ProfilingTest {
    private fun tmp(): File = Files.createTempDirectory("phx").toFile().also { it.deleteOnExit() }
    private fun row(name: String, host: String, days: Int = 1) =
        ConfigRow("trojan://pw@$host:443#$name", "trojan", name, "DE", null, days)

    @Test
    fun reliabilityIsSmoothedAndBadNeedsEvidence() {
        val fresh = HistStat(0, 0, 0)
        assertEquals(0.5, fresh.reliability, 1e-9)
        assertFalse(HistStat(0, 2, 0).bad) // двух неудач мало
        assertTrue(HistStat(0, 3, 0).bad)
        assertFalse(HistStat(5, 3, 0).bad)
    }

    @Test
    fun oldResultsFadeAway() {
        var s = HistStat(0, 0, 0)
        repeat(30) { s = s.after(false, 1) }
        assertTrue(s.total <= HistStat.MAX_TOTAL)
        repeat(30) { s = s.after(true, 2) }
        // после длинной серии удач прежние неудачи почти забыты
        assertTrue(s.reliability > 0.7)
    }

    @Test
    fun historyIsStoredPerNetworkAndSurvivesRestart() {
        val dir = tmp()
        val h = ConfigHistory(File(dir, "h.tsv"))
        h.record("trojan|germany 1", "wifi", true, 20.0)
        h.record("trojan|germany 1", "wifi", true)
        h.record("trojan|germany 1", "mobile:мтс", false)
        h.record("trojan|germany 1", "mobile:мтс", false)
        h.record("trojan|germany 1", "mobile:мтс", false)
        val wifi = h.view("wifi")("trojan|germany 1")!!
        val mts = h.view("mobile:мтс")("trojan|germany 1")!!
        assertEquals(2, wifi.ok)
        assertEquals(20.0, wifi.mbps, 1e-9)
        assertTrue(mts.bad)
        assertNull(h.view("cable")("trojan|germany 1"))
        val places = h.placesFor("trojan|germany 1")
        assertEquals(2, places.size)
        assertTrue(NetLabel.summary(places).contains("Wi-Fi 2✓/0✗"))
        assertTrue(NetLabel.summary(places).contains("моб. мтс 0✓/3✗"))
    }

    @Test
    fun staleEntriesAreForgotten() {
        val dir = tmp()
        val h = ConfigHistory(File(dir, "old.tsv"))
        val longAgo = System.currentTimeMillis() - 100L * 86_400_000L
        h.record("a|old one", "wifi", true, now = longAgo)
        h.record("a|new one", "wifi", true) // запись вытесняет просроченные
        assertNull(h.view("wifi")("a|old one"))
        assertNotNull(h.view("wifi")("a|new one"))
    }

    @Test
    fun rankingDemotesConfigsThatFailInThisNetwork() {
        val rows = listOf(row("Alpha Server", "1.1.1.1"), row("Beta Server", "2.2.2.2"), row("Gamma Server", "3.3.3.3"))
        val checks = emptyMap<String, CheckResult>()
        val alphaId = ConfigIdentity.key(rows[0].raw)
        val betaId = ConfigIdentity.key(rows[1].raw)
        val hist: (String) -> HistStat? = { id ->
            when (id) {
                alphaId -> HistStat(0, 5, 0) // в этой сети подводил
                betaId -> HistStat(8, 0, 0) // стабильно работал
                else -> null
            }
        }
        val ranked = Ranking.rank(Ranking.candidates(rows, checks, LocalDate.now(), hist))
        assertEquals("Beta Server", rows.first { it.raw == ranked[0].link }.name)
        assertEquals("Alpha Server", rows.first { it.raw == ranked.last().link }.name)
        // без опыта порядок прежний (никого не наказываем и не выделяем)
        val plain = Ranking.rank(Ranking.candidates(rows, checks, LocalDate.now()))
        assertEquals(3, plain.size)
        assertFalse(plain.any { it.bad })
    }

    @Test
    fun netLabels() {
        assertEquals("wifi", NetLabel.of("WIFI", null))
        assertEquals("mobile:мтс ru", NetLabel.of("MOBILE", "  МТС  RU "))
        assertEquals("mobile:?", NetLabel.of("MOBILE", ""))
        assertEquals("моб. сеть", NetLabel.title("mobile:?"))
    }

    // ---- пресет обхода для чёрных списков ----

    @Test
    fun openServicesPresetIsOnlyForBlacklistByDefault() {
        val bl = BypassStore(tmp(), Mode.BLACKLIST)
        val wl = BypassStore(tmp(), Mode.WHITELIST)
        assertTrue(bl.openEnabled)
        assertFalse(wl.openEnabled)
        assertTrue(bl.siteRules().contains("domain:github.com"))
        assertFalse(wl.siteRules().contains("domain:github.com"))
        assertTrue(bl.effective().contains("org.wikipedia"))
        bl.openEnabled = false
        assertFalse(bl.siteRules().contains("domain:github.com"))
        wl.openEnabled = true
        assertTrue(wl.siteRules().contains("domain:steampowered.com"))
    }

    @Test
    fun openServicesNeverContainBlockedOnes() {
        val blocked = listOf("telegram", "t.me", "youtube", "googlevideo", "instagram", "facebook", "twitter", "x.com", "discord", "chatgpt", "openai", "claude", "anthropic", "ytimg")
        for (d in OpenServices.DOMAINS) for (b in blocked) assertFalse("$d похож на заблокированный $b", d.contains(b))
        for (p in OpenServices.PACKAGES) for (b in listOf("telegram", "youtube", "instagram", "facebook", "discord", "openai", "anthropic")) assertFalse(p.contains(b))
    }

    // ---- адреса проверки для любых приложений ----

    @Test
    fun everyAppGetsACheckUrl() {
        assertEquals("https://web.telegram.org/", KnownApps.urlOrDefault("org.telegram.messenger"))
        assertEquals(KnownApps.DEFAULT_URL, KnownApps.urlOrDefault("com.some.unknown.app"))
        assertTrue(KnownApps.isKnown("com.anthropic.claude"))
        assertFalse(KnownApps.isKnown("com.some.unknown.app"))
        assertTrue(KnownApps.DEFAULT_URL.startsWith("https://"))
    }

    @Test
    fun softProbeAcceptsProtectionPagesButNotServerErrors() {
        assertTrue(Soft.accepts(200))
        assertFalse(Soft.accepts(403)) // голый 403 — возможно, блок по стране выхода
        assertTrue(Soft.accepts(403, "<title>Just a moment...</title>")) // проверка Cloudflare: сервер дотянулся
        assertTrue(Soft.accepts(404))
        assertFalse(Soft.accepts(502))
        assertFalse(Soft.accepts(-1))
    }

    @Test
    fun verifierUsesSoftProbeWhenStrictOneFails() {
        val pool = listOf(
            Candidate("trojan://pw@1.1.1.1:443#Alpha Server", ConfigIdentity.key("trojan://pw@1.1.1.1:443#Alpha Server"), 0, -1.0, 0),
            Candidate("trojan://pw@2.2.2.2:443#Beta Server", ConfigIdentity.key("trojan://pw@2.2.2.2:443#Beta Server"), 0, -1.0, 0),
        )
        val target = Target.app("com.anthropic.claude", "Claude", "https://claude.ai/")
        val strictOnly = TargetVerifier({ _, _ -> -1L }, parallel = 2)
        assertTrue(kotlinx.coroutines.runBlocking { strictOnly.verify(target, pool) }.isEmpty())
        val withSoft = TargetVerifier({ _, _ -> -1L }, parallel = 2, soft = { _, _ -> true })
        assertEquals(2, kotlinx.coroutines.runBlocking { withSoft.verify(target, pool) }.size)
    }

    // ---- ключ Reality со знаком «+» ----

    @Test
    fun realityPublicKeyKeepsPlus() {
        val pbk = "ycPIUcY6ci2yi7+_OHc20e4gzxEdJTqpmeShxSuHRU"
        val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&pbk=$pbk&sid=ab12&sni=www.microsoft.com&fp=chrome#n"
        val json = XrayConfig.vpnConfig(link)
        // «+» (в т.ч. потерянный как пробел) приводится к base64url — «-»
        assertTrue(json.contains("\"publicKey\":\"ycPIUcY6ci2yi7-_OHc20e4gzxEdJTqpmeShxSuHRU\""))
        assertFalse(json.contains("yi7 _OHc"))
        // процентное кодирование по-прежнему разворачивается
        val enc = XrayConfig.vpnConfig("vless://id@1.2.3.4:443?type=ws&security=tls&path=%2Fa%2Bb&sni=x.com#n")
        assertTrue(enc.contains("\"path\":\"/a+b\""))
    }
}

class SlowMonitorTest {
    @Test
    fun needsTwoSlowSamplesInARow() {
        val m = SlowMonitor(0.5)
        assertFalse(m.report(0.1, 0))
        assertTrue(m.report(0.1, 60_000))
    }

    @Test
    fun goodSampleResetsStrikes() {
        val m = SlowMonitor(0.5)
        assertFalse(m.report(0.1, 0))
        assertFalse(m.report(3.0, 60_000))
        assertFalse(m.report(0.1, 120_000))
        assertTrue(m.report(0.0, 180_000))
    }

    @Test
    fun cooldownAndHourlyLimitStopEndlessHopping() {
        val m = SlowMonitor(0.5, cooldownMs = 5 * 60_000L, maxPerHour = 2)
        var t = 0L
        m.report(0.1, t); assertTrue(m.report(0.1, t + 1))
        m.switched(t + 1)
        t = 60_000
        m.report(0.1, t); assertFalse(m.report(0.1, t + 1)) // ещё идёт пауза
        t = 6 * 60_000
        m.report(0.1, t); assertTrue(m.report(0.1, t + 1))
        m.switched(t + 1)
        t = 12 * 60_000
        m.report(0.1, t); assertFalse(m.report(0.1, t + 1)) // лимит 2 в час
        t = 70 * 60_000
        m.report(0.1, t); assertTrue(m.report(0.1, t + 1)) // час прошёл
    }

    @Test
    fun newServerMustBeClearlyBetter() {
        assertFalse(SlowMonitor.betterEnough(0.6, 0.2, 0.5))
        assertTrue(SlowMonitor.betterEnough(1.2, 0.2, 0.5))
        assertEquals(8.0, SlowMonitor.passiveRateMbps(1_000_000, 1.0), 1e-9)
    }
}
