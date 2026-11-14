package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TargetsTest {
    private fun link(name: String, host: String) = "trojan://pw@$host:443#$name"
    private fun cand(name: String, host: String, days: Int = 0, mbps: Double = -1.0, ms: Long = 0) =
        Candidate(link(name, host), ConfigIdentity.key(link(name, host)), days, mbps, ms)

    @Test
    fun siteTargetParsing() {
        val t = Target.site("https://www.Example.com/some/page?x=1")!!
        assertEquals("SITE:example.com", t.id)
        assertEquals("example.com", t.value)
        assertEquals("https://www.Example.com/some/page?x=1", t.checkUrl)
        assertEquals("https://claude.ai/", Target.site("claude.ai")!!.checkUrl)
        assertNull(Target.site("not a site"))
        assertNull(Target.site(""))
        assertEquals("https://web.telegram.org/", KnownApps.checkUrlFor("org.telegram.messenger"))
    }

    @Test
    fun domainHelpers() {
        assertEquals("sub.example.org", Domains.host("http://user@Sub.Example.org:8080/x"))
        assertEquals("domain:example.org", Domains.rule("example.org"))
        assertNull(Domains.host("localhost"))
        assertTrue(RussianSites.DOMAINS.contains("domain:ru"))
    }

    @Test
    fun storeKeepsPolicyWhenServersUpdated() {
        val f = File.createTempFile("targets", ".tsv").also { it.deleteOnExit() }
        val store = TargetStore(f)
        assertNull(store.add(Target.app("org.telegram.messenger", "Telegram", "https://web.telegram.org/")))
        assertEquals("Уже в списке", store.add(Target.app("org.telegram.messenger", "Telegram", "https://web.telegram.org/")))
        assertEquals("Нужен адрес проверки вида https://…", store.add(Target.app("x.y", "XY", "nope")))
        val t = store.load().single()
        store.update(t.copy(policy = Policy.SWITCH))
        store.updateServers(t.id, listOf(link("One Server", "1.1.1.1"), link("Two Server", "2.2.2.2")), "2026-10-14")
        val back = store.load().single()
        assertEquals(Policy.SWITCH, back.policy)
        assertEquals(2, back.servers.size)
        assertEquals("2026-10-14", back.verifiedAt)
        store.remove(t.id)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun setUrlKeepsServersFoundSinceScreenWasOpened() {
        // сценарий находки Фейбла: экран открыт со старым Target (без серверов), фон за это время нашёл серверы —
        // смена адреса проверки не должна их стирать (в отличие от update(t.copy(checkUrl = …)) со старым t)
        val f = File.createTempFile("targets", ".tsv").also { it.deleteOnExit() }
        val store = TargetStore(f)
        assertNull(store.add(Target.app("org.telegram.messenger", "Telegram", "https://web.telegram.org/")))
        val staleFromScreen = store.load().single() // как будто экран открыли до фоновой проверки
        store.updateServers(staleFromScreen.id, listOf(link("One Server", "1.1.1.1")), "2026-10-14")
        store.setUrl(staleFromScreen.id, "https://web.telegram.org/k/")
        val back = store.load().single()
        assertEquals("https://web.telegram.org/k/", back.checkUrl)
        assertEquals(1, back.servers.size)
        assertEquals("2026-10-14", back.verifiedAt)
    }

    @Test
    fun effectivePolicyUsesGlobalForDefault() {
        val t = Target.site("example.com")!!
        assertEquals(Policy.SWITCH, t.effective(Policy.SWITCH))
        assertEquals(Policy.AUTO, t.effective(Policy.DEFAULT))
        assertEquals(Policy.PROXY, t.copy(policy = Policy.PROXY).effective(Policy.SWITCH))
    }

    @Test
    fun rankingPrefersStableThenSpeedThenLatency() {
        val a = cand("Alpha Server", "1.1.1.1", days = 5, mbps = 5.0, ms = 300)
        val b = cand("Bravo Server", "2.2.2.2", days = 5, mbps = 20.0, ms = 500)
        val c = cand("Charlie Server", "3.3.3.3", days = 1, mbps = 90.0, ms = 100)
        val d = cand("Delta Server", "4.4.4.4", days = 5, mbps = 20.0, ms = 200)
        val e = cand("Echo Server", "5.5.5.5", days = 5)
        assertEquals(listOf("Delta Server", "Bravo Server", "Alpha Server", "Echo Server", "Charlie Server"),
            Ranking.rank(listOf(a, b, c, d, e)).map { LinkParser.parse(it.link)!!.title })
    }

    @Test
    fun candidatesDropDeadConfigs() {
        fun row(name: String, host: String) = ConfigRow(link(name, host), "trojan", name, "DE", null, 4)
        val alive = row("Alive Server", "1.1.1.1")
        val dead = row("Dead Server", "2.2.2.2")
        val checks = mapOf(
            CheckStore.keyOf(alive.raw) to CheckResult("d", 120, emptyMap(), 30.0, "1.1.1.1:443"),
            CheckStore.keyOf(dead.raw) to CheckResult("d", -1, emptyMap()),
        )
        val c = Ranking.candidates(listOf(alive, dead), checks)
        assertEquals(1, c.size)
        assertEquals(30.0, c[0].mbps, 0.001)
        assertEquals(4, c[0].days)
    }

    @Test
    fun deadConfigGetsSecondChanceWhenOldOrMoved() {
        val today = java.time.LocalDate.of(2026, 10, 14)
        val row = ConfigRow(link("Flaky Server", "1.1.1.1"), "trojan", "Flaky Server", "DE", null, 4)
        val key = CheckStore.keyOf(row.raw)
        val host = ConfigIdentity.address(row.raw)
        fun cands(r: CheckResult) = Ranking.candidates(listOf(row), mapOf(key to r), today).size
        assertEquals(0, cands(CheckResult("2026-10-13", -1, emptyMap(), host = host)))  // вчера не отвечал
        assertEquals(1, cands(CheckResult("2026-10-10", -1, emptyMap(), host = host)))  // проверка старая
        assertEquals(1, cands(CheckResult("2026-10-14", -1, emptyMap(), host = "9.9.9.9:443"))) // сервер сменился
    }

    @Test
    fun failoverSkipsTriedAndCurrent() {
        val list = listOf(cand("One Server", "1.1.1.1", 5), cand("Two Server", "2.2.2.2", 4), cand("Three Server", "3.3.3.3", 3))
        val order = Ranking.failoverOrder(list, tried = setOf(list[1].identity), currentIdentity = list[0].identity)
        assertEquals(listOf(list[2].identity), order.map { it.identity })
        assertEquals(1, Ranking.failoverOrder(list, emptySet(), "none", limit = 1).size)
    }

    @Test
    fun verifierKeepsOldServersFirstAndStopsAtThree() {
        val calls = AtomicInteger()
        val good = setOf("keep.example", "b.example", "c.example", "d.example")
        val probe: (String, String) -> Long = { config, _ ->
            calls.incrementAndGet()
            if (good.any { config.contains(it) }) 50 else -1
        }
        val target = Target.app("org.telegram.messenger", "Telegram", "https://web.telegram.org/")
            .copy(servers = listOf(link("Keeper Server", "keep.example")))
        val cands = listOf(
            cand("Bad One", "bad1.example", 5, 50.0), cand("Bad Two", "bad2.example", 5, 40.0),
            cand("Keeper Server", "keep.example", 0), // тот же сервер найден по имени
            cand("Beta Server", "b.example", 3, 10.0), cand("Cee Server", "c.example", 3, 5.0), cand("Dee Server", "d.example", 3, 1.0),
        )
        val servers = runBlocking { TargetVerifier(probe, parallel = 2, keep = 3).verify(target, cands) }
        assertEquals(3, servers.size)
        assertTrue(servers[0].contains("keep.example")) // прежний сервер остаётся первым
        assertTrue(calls.get() <= 12)
    }

    @Test
    fun verifierUsesFreshAddressForSameName() {
        val seen = ArrayList<String>()
        val probe: (String, String) -> Long = { config, _ -> synchronized(seen) { seen += config }; 10 }
        val target = Target.app("p.q", "PQ", "https://x.test/").copy(servers = listOf(link("Moving Server", "old.example")))
        val servers = runBlocking {
            TargetVerifier(probe, parallel = 1, keep = 1).verify(target, listOf(cand("Moving Server", "new.example")))
        }
        assertTrue(servers.single().contains("new.example"))
        assertFalse(seen.any { it.contains("old.example") })
    }

    @Test
    fun verifierSkipsUnsupportedLinks() {
        val target = Target.app("p.q", "PQ", "https://x.test/")
        val bad = Candidate("vless://id@a.com:443?type=kcp#Kcp Server", ConfigIdentity.key("vless://id@a.com:443?type=kcp#Kcp Server"), 9, 99.0, 1)
        val servers = runBlocking { TargetVerifier({ _, _ -> 5 }, keep = 2).verify(target, listOf(bad, cand("Good Server", "g.example"))) }
        assertEquals(1, servers.size)
        assertTrue(servers[0].contains("g.example"))
    }

    @Test
    fun routesOnlyForTargetsMainDoesNotCover() {
        val main = link("Main Server", "main.example")
        val tg = Target.app("org.telegram.messenger", "TG", "https://web.telegram.org/").copy(servers = listOf(link("Tg Server", "tg.example")))
        val covered = Target.app("com.google.android.youtube", "YT", "https://y/").copy(servers = listOf(link("Main Server", "other-address.example")))
        val noServers = Target.app("com.whatsapp", "WA", "https://w/")
        val switchOnly = Target.app("com.discord", "DC", "https://d/").copy(policy = Policy.SWITCH, servers = listOf(link("Dc Server", "dc.example")))
        val site = Target.site("claude.ai")!!.copy(servers = listOf(link("Cl Server", "cl.example")))
        val missingApp = Target.app("not.installed", "NI", "https://n/").copy(servers = listOf(link("Ni Server", "ni.example")))
        val uid = mapOf("org.telegram.messenger" to "10123")
        val routes = PlanBuilder.routes(main, listOf(tg, covered, noServers, switchOnly, site, missingApp), Policy.PROXY) { uid[it] }
        assertEquals(2, routes.size)
        assertEquals(listOf("10123"), routes[0].uids)
        assertEquals("t0", routes[0].tag)
        assertEquals(listOf("domain:claude.ai"), routes[1].domains)
        assertEquals("t1", routes[1].tag)
    }

    @Test
    fun switchOnlyForForegroundAppWithSwitchPolicyAndCooldown() {
        val main = link("Main Server", "main.example")
        val dc = Target.app("com.discord", "DC", "https://d/").copy(policy = Policy.SWITCH, servers = listOf(link("Dc Server", "dc.example")))
        val t = listOf(dc)
        assertEquals(dc, PlanBuilder.switchFor("com.discord", t, Policy.PROXY, main, emptyMap(), 1_000_000))
        assertNull(PlanBuilder.switchFor("com.other", t, Policy.PROXY, main, emptyMap(), 1_000_000))
        assertNull(PlanBuilder.switchFor("com.discord", t, Policy.PROXY, link("Dc Server", "x.example"), emptyMap(), 1_000_000)) // уже на нём
        assertNull(PlanBuilder.switchFor("com.discord", t, Policy.PROXY, main, mapOf(dc.id to 1_000_000L - 60_000), 1_000_000)) // кулдаун
        assertEquals(dc, PlanBuilder.switchFor("com.discord", t, Policy.PROXY, main, mapOf(dc.id to 1_000_000L - 130_000), 1_000_000))
        val def = dc.copy(policy = Policy.DEFAULT)
        assertNull(PlanBuilder.switchFor("com.discord", listOf(def), Policy.PROXY, main, emptyMap(), 1))
        assertEquals(def, PlanBuilder.switchFor("com.discord", listOf(def), Policy.SWITCH, main, emptyMap(), 1))
    }

    @Test
    fun vpnConfigWithRoutesBypassAndHealth() {
        val opts = XrayOptions(
            routes = listOf(
                AppRoute("t0", link("Tg Server", "tg.example"), uids = listOf("10123")),
                AppRoute("t1", link("Cl Server", "cl.example"), domains = listOf("domain:claude.ai")),
                AppRoute("t2", "vless://id@a.com:443?type=kcp#Bad", uids = listOf("1")),
            ),
            bypassDomains = RussianSites.DOMAINS + "domain:example.org",
            healthPort = 18090,
        )
        val json = XrayConfig.vpnConfig(link("Main Server", "main.example"), opts)
        System.getProperty("phoenix.dump")?.let { File(it, "routes.json").writeText(json) }
        assertTrue(Json.looksLikeObject(json))
        assertTrue(json.contains("\"tag\":\"t0\""))
        assertTrue(json.contains("\"tag\":\"t1\""))
        assertFalse(json.contains("\"tag\":\"t2\"")) // неподдерживаемую ссылку пропускаем
        assertTrue(json.contains("\"process\":[\"10123\"],\"outboundTag\":\"t0\""))
        assertTrue(json.contains("\"domain\":[\"domain:claude.ai\"],\"outboundTag\":\"t1\""))
        assertTrue(json.contains("\"domain:ru\""))
        assertTrue(json.contains("\"port\":18090"))
        assertTrue(json.contains("\"inboundTag\":[\"health\"],\"outboundTag\":\"proxy\""))
        // правило здоровья и обхода идут раньше правил целей
        assertTrue(json.indexOf("\"inboundTag\"") < json.indexOf("domain:ru"))
        assertTrue(json.indexOf("domain:ru") < json.indexOf("\"process\""))
    }

    @Test
    fun socksConfigForSpeedTest() {
        val json = XrayConfig.socksConfig(link("Speed Server", "s.example"), 19000)
        System.getProperty("phoenix.dump")?.let { File(it, "socks.json").writeText(json) }
        assertTrue(json.contains("\"protocol\":\"socks\""))
        assertTrue(json.contains("\"listen\":\"127.0.0.1\""))
        assertTrue(json.contains("\"port\":19000"))
        assertFalse(json.contains("\"protocol\":\"tun\""))
    }

    @Test
    fun blockDetectorClassification() {
        assertEquals(BlockType.BLACKLIST, BlockDetector.classify(ProbeCounts(5, 6, 4, 4)))
        assertEquals(BlockType.WHITELIST, BlockDetector.classify(ProbeCounts(0, 6, 4, 4)))
        assertEquals(BlockType.WHITELIST, BlockDetector.classify(ProbeCounts(1, 6, 3, 4)))
        assertEquals(BlockType.OFFLINE, BlockDetector.classify(ProbeCounts(0, 6, 0, 4)))
        assertEquals(BlockType.UNKNOWN, BlockDetector.classify(ProbeCounts(3, 6, 4, 4)))
        assertEquals(BlockType.UNKNOWN, BlockDetector.classify(ProbeCounts(0, 6, 1, 4)))
    }

    @Test
    fun modeSwitchesOnlyAfterTwoSameResultsInRow() {
        var vote = Vote(BlockType.UNKNOWN, 0)
        var (v1, sw1) = ModeDecider.step(vote, BlockType.BLACKLIST, Mode.WHITELIST)
        assertNull(sw1)
        val (v2, sw2) = ModeDecider.step(v1, BlockType.BLACKLIST, Mode.WHITELIST)
        assertEquals(Mode.BLACKLIST, sw2)
        // «не ясно» и «нет сети» ничего не меняют, счёт сохраняется
        val (v3, sw3) = ModeDecider.step(v2, BlockType.OFFLINE, Mode.BLACKLIST)
        assertNull(sw3)
        assertEquals(v2, v3)
        // чередование сбрасывает счёт
        val (v4, _) = ModeDecider.step(Vote(BlockType.WHITELIST, 1), BlockType.BLACKLIST, Mode.WHITELIST)
        assertEquals(1, v4.count)
        // тот же режим — переключать нечего
        assertNull(ModeDecider.step(Vote(BlockType.WHITELIST, 5), BlockType.WHITELIST, Mode.WHITELIST).second)
        val f = File.createTempFile("vote", ".txt").also { it.deleteOnExit() }
        ModeDecider.save(f, v2)
        assertEquals(v2, ModeDecider.load(f))
    }
}

class BypassSitesTest {
    @Test
    fun sitesRulesAndPreset() {
        val dir = java.nio.file.Files.createTempDirectory("bypass2").toFile()
        val store = BypassStore(dir)
        assertNull(store.addSite("https://www.Example.org/path"))
        assertEquals("Уже в списке", store.addSite("example.org"))
        assertEquals("Нужен адрес сайта вида example.com", store.addSite("not a site"))
        assertTrue(store.siteRules().contains("domain:ru"))
        assertTrue(store.siteRules().contains("domain:example.org"))
        store.presetEnabled = false
        assertEquals(listOf("domain:example.org"), store.siteRules())
        store.removeSite("example.org")
        assertTrue(store.siteRules().isEmpty())
        dir.deleteRecursively()
    }
}
