package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3955Test {
    private fun tmp() = File.createTempFile("res", ".tsv").also { it.delete() }
    private fun cand(i: Int, rel: Double = 0.5) = Candidate(link = "vless://u@h$i:443?type=tcp#n$i", identity = "id$i", days = 1, mbps = 1.0, latencyMs = 100, rel = rel)

    @Test fun flapGuardTriggersOnFrequentChanges() {
        val g = FlapGuard()
        g.note(0)
        assertFalse(g.flapping(1_000))
        g.note(60_000)
        assertTrue(g.flapping(61_000))
        assertFalse(g.flapping(60_000 + FlapGuard.WINDOW_MS + 200_000))
    }

    @Test fun lastGoodRememberedPerNetAndMode() {
        val f = tmp()
        val s = LastGoodStore(f)
        s.remember("wifi", "WHITE", "vless://a", 1_000)
        s.remember("mobile", "WHITE", "vless://b", 2_000)
        s.remember("wifi", "WHITE", "vless://c", 3_000)
        assertEquals("vless://c", s.get("wifi", "WHITE", 4_000))
        assertEquals("vless://b", s.get("mobile", "WHITE", 4_000))
        assertNull(s.get("wifi", "BLACK", 4_000))
        assertNull(s.get("wifi", "WHITE", 4_000 + LastGoodStore.MAX_AGE_MS + 1))
        assertEquals("vless://c", LastGoodStore(f).get("wifi", "WHITE", 5_000))
    }

    @Test fun downtimeStatsAccumulateAndRejectInsane() {
        val s = DowntimeStats(tmp())
        assertTrue(s.add(DowntimeStats.MODE, 40, "2026-11-07"))
        assertTrue(s.add(DowntimeStats.FAILOVER, 20, "2026-11-07"))
        assertFalse(s.add(DowntimeStats.MODE, DowntimeStats.MAX_SEC + 1, "2026-11-07"))
        assertFalse(s.add(DowntimeStats.MODE, -1, "2026-11-07"))
        val sum = s.summary("2026-11-07")
        assertTrue(sum, sum.contains("перерывов 2"))
        assertTrue(sum, sum.contains("1 мин"))
    }

    @Test fun lastGoodGoesFirstInQuickOrder() {
        val ranked = listOf(cand(1), cand(2, rel = 0.9), cand(3))
        val order = QuickOrder.order(ranked, null, 3, cand(3).link)
        assertEquals(cand(3).link, order.first())
        assertEquals(3, order.size)
        assertEquals(cand(2).link, QuickOrder.order(ranked, null, 3, "vless://x@gone:1#z").first())
    }

    @Test fun cookiesFollowDomainAndRespectExpiry() {
        val j = SimpleCookies()
        j.add("gemini.google.com", "NID=abc; Path=/; Domain=.google.com; HttpOnly")
        j.add("consent.google.com", "CONSENT=1; Path=/")
        assertEquals("NID=abc", j.headerFor("accounts.google.com"))
        assertTrue(j.headerFor("consent.google.com").contains("CONSENT=1"))
        assertEquals("", j.headerFor("example.com"))
        assertEquals("", j.headerFor("evilgoogle.com"))
        j.add("gemini.google.com", "NID=; Max-Age=0; Domain=google.com")
        assertEquals("", j.headerFor("accounts.google.com"))
    }

    @Test fun foreignCookieDomainIsIgnored() {
        val j = SimpleCookies()
        j.add("a.example.com", "x=1; Domain=other.org")
        assertEquals("x=1", j.headerFor("a.example.com"))
        assertEquals("", j.headerFor("other.org"))
    }

    @Test fun googleDiagRecognisesGoogleAiOnly() {
        assertTrue(GoogleDiag.relevant("com.google.android.apps.bard", null))
        assertTrue(GoogleDiag.relevant("com.android.chrome", "gemini.google.com"))
        assertTrue(GoogleDiag.relevant(null, "notebooklm.google.com"))
        assertFalse(GoogleDiag.relevant("com.android.chrome", "mail.google.com"))
        assertFalse(GoogleDiag.relevant("org.telegram.messenger", null))
        assertFalse(GoogleDiag.relevant(null, "evilgemini.google.com.example"))
    }

    @Test fun googleDiagPeriodAndLine() {
        assertFalse(GoogleDiag.due(1_000, 1_000 + GoogleDiag.PERIOD_MS - 1))
        assertTrue(GoogleDiag.due(1_000, 1_000 + GoogleDiag.PERIOD_MS))
        assertTrue(GoogleDiag.due(5_000, 1_000))
        val l = GoogleDiag.line("gemini", 200, 350, listOf("gemini.google.com", "accounts.google.com"), true, null)
        assertTrue(l, l.contains("код 200") && l.contains("→") && l.contains("НЕ ПОДДЕРЖИВАЕТСЯ"))
        assertTrue(GoogleDiag.line("вход", 0, 8000, emptyList(), false, "timeout").contains("ошибка"))
    }

    @Test fun netFlapDefersOnlyBriefly() {
        NetFlap.clear()
        assertFalse(NetFlap.busy(1_000))
        NetFlap.mark(1_000)
        assertTrue(NetFlap.busy(1_000 + NetFlap.DEFER_MS - 1))
        assertFalse(NetFlap.busy(1_000 + NetFlap.DEFER_MS))
        NetFlap.clear()
    }

    @Test fun diagHintsFindUnsupportedPhrases() {
        val h = GoogleDiag.hintsIn("<html>... Gemini isn't currently supported in your country ...</html>")
        assertTrue(h, h.contains("supported"))
        assertEquals("", GoogleDiag.hintsIn("<html>hello world</html>"))
        assertTrue(GoogleDiag.line("g", 200, 5, emptyList(), false, null, "«Gemini» вход").contains("текст: «Gemini»"))
    }

    private fun gt(id: String, pkg: String, servers: List<String>) = Target(id, TargetKind.APP, pkg, id, "https://x/", Policy.PROXY, servers)

    @Test fun googleAiTargetsShareOneServerAndFamilyDomains() {
        val a = "vless://u@a.test:443?type=tcp#A"
        val b = "vless://u@b.test:443?type=tcp#B"
        val c = "vless://u@c.test:443?type=tcp#C"
        val gem = gt("gem", "com.google.android.apps.bard", listOf(a, b))
        val nb = gt("nb", "com.google.android.apps.labs.language.tailwind", listOf(c, b))
        val routes = PlanBuilder.routes("vless://m@main.test:443#M", listOf(gem, nb), Policy.PROXY) { "10" + it.length }
        assertEquals(2, routes.size)
        assertEquals(b, routes[0].link)
        assertEquals(b, routes[1].link)
        assertTrue(routes[0].domains.any { it.contains("googleapis.com") })
        assertFalse(routes[1].domains.any { it.contains("googleapis.com") })
    }

    @Test fun googleAiWithoutCommonServerKeepsOwnServers() {
        val gem = gt("gem", "com.google.android.apps.bard", listOf("vless://u@a.test:443#A"))
        val nb = gt("nb", "com.google.android.apps.labs.language.tailwind", listOf("vless://u@c.test:443#C"))
        val routes = PlanBuilder.routes("vless://m@main.test:443#M", listOf(gem, nb), Policy.PROXY) { "10" + it.length }
        assertEquals(listOf("vless://u@a.test:443#A", "vless://u@c.test:443#C"), routes.map { it.link })
    }

    @Test fun otherAppsDoNotGetGoogleFamily() {
        val tg = gt("tg", "org.telegram.messenger", listOf("vless://u@a.test:443#A"))
        val routes = PlanBuilder.routes("vless://m@main.test:443#M", listOf(tg), Policy.PROXY) { "10" }
        assertFalse(routes.single().domains.any { it.contains("googleapis.com") })
    }

    @Test fun oracleOnlyForGeminiFamily() {
        assertTrue(GoogleAi.needsOracle("https://gemini.google.com/app"))
        assertTrue(GoogleAi.needsOracle("https://aistudio.google.com/"))
        assertFalse(GoogleAi.needsOracle("https://notebooklm.google.com/"))
        assertFalse(GoogleAi.needsOracle("https://claude.ai/"))
        assertFalse(GoogleAi.needsOracle("https://evilgemini.google.com.example/"))
    }

    @Test fun sorryHopMeansClosedForGoogle() {
        assertTrue(RegionBlock.abuseChain(listOf("gemini.google.com /app", "www.google.com /sorry/index", "gemini.google.com /app")))
        assertFalse(RegionBlock.abuseChain(listOf("gemini.google.com", "accounts.google.com /v3/signin/identifier")))
        assertFalse(RegionBlock.abuseChain(listOf("example.com /sorry/index")))
        assertFalse(RegionBlock.abuseChain(emptyList()))
    }

    @Test fun autoVetoGateLimitsSwitches() {
        val g = AutoVetoGate(3, 1_000)
        assertTrue(g.allow(0)); assertTrue(g.allow(10)); assertTrue(g.allow(20))
        assertFalse(g.allow(30))
        assertTrue(g.allow(2_000))
        g.reset()
        assertTrue(g.allow(2_001))
    }

    @Test fun diagLabelFollowsOpenedService() {
        assertEquals("notebook", GoogleDiag.labelFor("com.google.android.apps.labs.language.tailwind", null))
        assertEquals("notebook", GoogleDiag.labelFor(null, "notebooklm.google.com"))
        assertEquals("gemini", GoogleDiag.labelFor("com.google.android.apps.bard", null))
        assertEquals("gemini", GoogleDiag.labelFor(null, "gemini.google.com"))
    }

    @Test fun googleAppCountsAsGemini() {
        assertEquals("https://gemini.google.com/", KnownApps.checkUrlFor("com.google.android.googlequicksearchbox"))
        assertTrue(GoogleDiag.relevant("com.google.android.googlequicksearchbox", null))
        assertEquals("gemini", GoogleDiag.labelFor("com.google.android.googlequicksearchbox", null))
    }

    @Test fun repeatMuteSuppressesIdenticalLines() {
        val m = RepeatMute(1_000)
        assertTrue(m.shouldLog("k", "a", 0))
        assertFalse(m.shouldLog("k", "a", 500))
        assertTrue(m.shouldLog("k", "b", 600))
        assertFalse(m.shouldLog("k", "b", 900))
        assertTrue(m.shouldLog("k", "b", 1_700))
        assertTrue(m.shouldLog("other", "b", 1_701))
    }

    @Test fun finalHintKeepsOnlySafeParams() {
        val h = GoogleDiag.finalHint("https://notebook.google/?location=unsupported&token=SECRET123&hl=ru")
        assertEquals("notebook.google ?location=unsupported&hl=ru", h)
        assertFalse(h.contains("SECRET"))
        assertEquals("", GoogleDiag.finalHint("not a url"))
    }

    @Test fun queueMixInsertsForeignEveryTwo() {
        assertEquals(listOf("a", "b", "W1", "c", "d", "W2", "e"), QueueMix.mix(listOf("a", "b", "c", "d", "e"), listOf("W1", "W2"), 2))
        assertEquals(listOf("a", "W1", "W2"), QueueMix.mix(listOf("a"), listOf("W1", "W2"), 2))
        assertEquals(listOf("a", "b"), QueueMix.mix(listOf("a", "b"), emptyList<String>(), 2))
        assertEquals(listOf("W1"), QueueMix.mix(emptyList<String>(), listOf("W1"), 2))
    }

    @Test fun googleDiagFallbackCheckUrlOnlyForRelevantApps() {
        assertEquals("https://gemini.google.com/app", GoogleDiag.fallbackCheckUrl("com.google.android.apps.bard", null))
        assertEquals("https://notebooklm.google.com/", GoogleDiag.fallbackCheckUrl("com.google.android.apps.labs.language.tailwind", null))
        assertEquals("https://notebooklm.google.com/", GoogleDiag.fallbackCheckUrl(null, "notebooklm.google.com"))
        assertNull(GoogleDiag.fallbackCheckUrl("org.telegram.messenger", null))
        assertNull(GoogleDiag.fallbackCheckUrl(null, null))
    }

    @Test fun configIdentityHandlesLongWhitespaceQuickly() {
        // находка Фейбла №1.2: \s*\|?\s* в начале geoSuffix на длинной серии пробелов перебирает кубически —
        // на реальном имени из источника с сотнями-тысячами пробелов (например, из %20) это занимало секунды
        // под opLock в failover; после обрезки входа это должно укладываться в десятки миллисекунд
        val padded = "Germany #3" + " ".repeat(5_000) + "tail"
        val t0 = System.currentTimeMillis()
        val name = ConfigIdentity.normalizeName(padded)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("normalizeName on long whitespace took ${elapsed}ms", elapsed < 2_000)
        assertTrue(name, name.startsWith("germany 3"))
    }

    @Test fun configIdentityNormalizesOrdinaryNamesUnchanged() {
        assertEquals("germany 3", ConfigIdentity.normalizeName("🇩🇪 Germany #3 | [DE] TG:ok QW:ok"))
        assertEquals("simple name test", ConfigIdentity.normalizeName("Simple_Name.test"))
    }

    @Test fun coldStartSoftensFirstRun() {
        assertEquals(4, ColdStart.recheckParallel(12, 0L))
        assertEquals(2, ColdStart.recheckParallel(4, 0L))
        assertEquals(12, ColdStart.recheckParallel(12, 1_700_000_000_000L))
        assertEquals(90L, ColdStart.catchUpDelaySec("catch-up", 0L))
        assertEquals(0L, ColdStart.catchUpDelaySec("manual", 0L))
        assertEquals(0L, ColdStart.catchUpDelaySec("catch-up", 5L))
    }
}
