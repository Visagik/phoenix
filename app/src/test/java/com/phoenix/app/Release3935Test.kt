package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3935Test {
    private fun cand(id: String, cc: String = "") = Candidate(link = "vless://$id@h:1", identity = id, days = 1, mbps = 1.0, latencyMs = 1, cc = cc)

    @Test fun browserUrlHost() {
        assertEquals("claude.ai", BrowserUrl.host("claude.ai/chat/abc?x=1"))
        assertEquals("t.me", BrowserUrl.host("https://t.me/durov"))
        assertEquals("example.com", BrowserUrl.host("https://www.example.com:8443/a"))
        assertNull(BrowserUrl.host("Поиск или адрес"))
        assertNull(BrowserUrl.host("погода в москве"))
        assertNull(BrowserUrl.host(""))
        assertNull(BrowserUrl.host(null))
        assertNull(BrowserUrl.host("localhost"))
    }

    @Test fun appDomains() {
        assertTrue(AppDomains.rules("org.telegram.messenger").contains("domain:t.me"))
        assertEquals("com.anthropic.claude", AppDomains.packageFor("claude.ai"))
        assertEquals("com.anthropic.claude", AppDomains.packageFor("api.anthropic.com"))
        assertNull(AppDomains.packageFor("example.org"))
        assertTrue(AppDomains.hosts("unknown.pkg").isEmpty())
        // общие домены не входят
        assertFalse(AppDomains.rules("com.google.android.youtube").contains("domain:google.com"))
    }

    @Test fun siteMatchesTargets() {
        val tg = Target.app("org.telegram.messenger", "Telegram", "https://web.telegram.org/")
        val cl = Target.app("com.anthropic.claude", "Claude", "https://claude.ai/")
        val site = Target.site("example.com")!!
        val all = listOf(tg, cl, site)
        assertEquals(cl.id, SiteMatch.targetFor("claude.ai", all)?.id)
        assertEquals(tg.id, SiteMatch.targetFor("t.me", all)?.id)
        assertEquals(site.id, SiteMatch.targetFor("shop.example.com", all)?.id)
        assertNull(SiteMatch.targetFor("unknown.org", all))
        // Telegram X вместо Telegram: тот же набор доменов
        val x = Target.app("org.thunderdog.challegram", "Telegram X", "https://web.telegram.org/")
        assertEquals(x.id, SiteMatch.targetFor("t.me", listOf(x))?.id)
    }

    @Test fun recentApps() {
        val ev = listOf("a" to 100L, "com.phoenix.app" to 900L, "b" to 800L, "launcher" to 950L, "a" to 700L, "old" to 1L, "com.google.android.inputmethod.latin" to 990L)
        val r = RecentApps.pick(ev, setOf("com.phoenix.app", "launcher"), now = 1000L, windowMs = 500L)
        assertEquals(listOf("b", "a"), r)
    }

    @Test fun currentSiteFreshness() {
        CurrentSite.clear()
        assertNull(CurrentSite.fresh(10L))
        CurrentSite.set("claude.ai", "com.android.chrome", 1000L)
        assertNotNull(CurrentSite.fresh(2000L))
        assertNull(CurrentSite.fresh(1000L + 11 * 60_000L))
        CurrentSite.clear()
        val p = "com.phoenix.app"
        assertTrue(CurrentSite.isListed("x/y:$p/.SiteWatcherService", p, "SiteWatcherService"))
        assertTrue(CurrentSite.isListed("$p/$p.SiteWatcherService", p, "SiteWatcherService"))
        assertFalse(CurrentSite.isListed(null, p, "SiteWatcherService"))
        assertFalse(CurrentSite.isListed("other/.Svc", p, "SiteWatcherService"))
    }

    @Test fun exitBlockedByCountry() {
        val claude = "https://claude.ai/"
        assertTrue(AppFit.exitBlocked(claude, null, "RU"))
        assertFalse(AppFit.exitBlocked(claude, null, "DE"))
        assertFalse(AppFit.exitBlocked(claude, null, null))
        val tg = CheckService("TG", "Telegram", "https://web.telegram.org/")
        assertTrue(AppFit.exitBlocked("https://web.telegram.org/", tg, "RU"))
        assertFalse(AppFit.exitBlocked("https://web.telegram.org/", tg, "NL"))
    }

    @Test fun arrangePutsRussianExitLast() {
        val own = cand("own")
        val other = cand("other")
        val ru = cand("ru")
        val nameRu = cand("nameru", cc = "RU")
        val rest = cand("rest")
        val measured = mapOf("ru" to "RU", "own" to "DE", "other" to "NL")
        val blocked = { cc: String? -> cc == "RU" }
        val out = AppFit.arrange(listOf(ru, nameRu, rest, other, own), setOf("own"), setOf("other"), { measured[it.identity] }, blocked).map { it.identity }
        assertEquals(listOf("own", "other", "rest", "nameru", "ru"), out)
    }

    @Test fun measuredOwnServerInBlockedCountryIsDemoted() {
        val own = cand("own")
        val rest = cand("rest")
        val out = AppFit.arrange(listOf(own, rest), setOf("own"), emptySet(), { if (it.identity == "own") "RU" else null }, { it == "RU" }).map { it.identity }
        assertEquals(listOf("rest", "own"), out)
    }

    @Test fun missBackoff() {
        val m = MissBackoff.parse(MissBackoff.render(mapOf("APP:x" to 1000L, "SITE:y" to 5L)))
        assertEquals(2, m.size)
        assertTrue(MissBackoff.skip(m, "APP:x", 1000L + 60_000L))
        assertFalse(MissBackoff.skip(m, "APP:x", 1000L + MissBackoff.TTL_MS + 1))
        assertFalse(MissBackoff.skip(m, "APP:none", 2000L))
        assertTrue(MissBackoff.parse("garbage\n\t\nx\tNaN").isEmpty())
    }

    @Test fun routesCarryAppDomains() {
        val tg = Target.app("org.telegram.messenger", "Telegram", "https://web.telegram.org/").copy(servers = listOf("vless://a@h1:1#A"), policy = Policy.PROXY)
        val routes = PlanBuilder.routes("vless://m@h2:1#M", listOf(tg), Policy.DEFAULT, finderOk = true, usageOk = false, uidOf = { "10123" })
        assertEquals(1, routes.size)
        assertEquals(listOf("10123"), routes[0].uids)
        assertTrue(routes[0].domains.contains("domain:t.me"))
    }
}
