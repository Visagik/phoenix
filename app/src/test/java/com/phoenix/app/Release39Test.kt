package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release39Test {
    private fun cand(id: String, prio: Int = 0, alive: Boolean = false, days: Int = 0) =
        Candidate("vless://$id", id, days, -1.0, 0, alive = alive, prio = prio)

    // ---- приоритеты ссылок ----

    @Test fun priorityRoundTrip() {
        val text = SourcePriority.encode(mapOf("https://a.example/x" to 1, "https://b.example/y" to 0, "https://c.example/z" to -1))
        val back = SourcePriority.decode(text)
        assertEquals(mapOf("https://a.example/x" to 1, "https://c.example/z" to -1), back)
    }

    @Test fun priorityPerConfigTakesHighestAndLowOnlyWhenAlone() {
        val members = mapOf("A" to setOf("1", "2"), "B" to setOf("2", "3"), "C" to setOf("3"))
        val per = SourcePriority.perConfig(members, mapOf("A" to SourcePriority.HIGH, "B" to SourcePriority.LOW, "C" to SourcePriority.LOW))
        assertEquals(1, per["1"])
        assertEquals(1, per["2"]) // есть в высокой — высокая
        assertEquals(-1, per["3"]) // только в низких
        val mixed = SourcePriority.perConfig(mapOf("B" to setOf("x"), "N" to setOf("x")), mapOf("B" to SourcePriority.LOW))
        assertEquals(0, mixed["x"]) // в обычной тоже есть — не понижается
    }

    @Test fun priorityFileStore() {
        val f = File.createTempFile("prio", ".tsv")
        try {
            val st = SourcePriorities(f)
            st.set("https://a.example/x", SourcePriority.HIGH)
            st.set("https://b.example/y", SourcePriority.LOW)
            assertEquals(2, st.load().size)
            st.set("https://a.example/x", SourcePriority.NORMAL)
            assertEquals(mapOf("https://b.example/y" to -1), st.load())
        } finally {
            f.delete()
        }
    }

    @Test fun rankingPutsHighPriorityFirstAmongSameAliveAndLowLast() {
        val ranked = Ranking.rank(listOf(cand("n1"), cand("low", prio = -1), cand("hi", prio = 1), cand("n2")))
        assertEquals("hi", ranked.first().identity)
        assertEquals("low", ranked.last().identity)
    }

    @Test fun aliveStillBeatsHighPriorityNotAlive() {
        val ranked = Ranking.rank(listOf(cand("hi", prio = 1), cand("alive", alive = true)))
        assertEquals("alive", ranked.first().identity)
    }

    // ---- доля рабочих ----

    @Test fun sourceStatPercents() {
        val members = mapOf("A" to setOf("1", "2", "3", "4"), "B" to setOf("5"))
        val stats = SourceStats.compute(members) { id -> when (id) { "1", "2", "3" -> CfgStatus.ALIVE; "4" -> CfgStatus.DEAD; else -> CfgStatus.UNKNOWN } }
        val a = stats.first { it.url == "A" }
        assertEquals(75, a.percent)
        assertEquals(-1, stats.first { it.url == "B" }.percent)
        assertEquals("A", stats.first().url) // лучшие по доле первыми
    }

    @Test fun sourceMembersRoundTrip() {
        val m = mapOf("https://a.example/x" to setOf("b", "a"), "https://c.example/y" to setOf("z"))
        assertEquals(m, SourceStats.decode(SourceStats.encode(m)))
    }

    // ---- расписание раз в N часов ----

    @Test fun intervalSchedule() {
        val h = Schedule.HOUR_MS
        assertTrue(Schedule.intervalDue(0, 1000, 12))
        assertFalse(Schedule.intervalDue(1000, 1000 + 11 * h, 12))
        assertTrue(Schedule.intervalDue(1000, 1000 + 12 * h, 12))
        assertEquals(1000 + 12 * h, Schedule.nextIntervalAt(1000, 2000, 12))
        // срок прошёл — повтор через полчаса, а не мгновенно
        assertEquals(50 * h + Schedule.INTERVAL_RETRY_MS, Schedule.nextIntervalAt(1000, 50 * h, 12))
        assertFalse(Schedule.intervalCatchUp(1000, 1000 + 12 * h - 1, 1000 + 12 * h - 1, 12))
        assertTrue(Schedule.intervalCatchUp(1000, 0, 1000 + 13 * h, 12))
        assertFalse(Schedule.intervalCatchUp(1000, 1000 + 13 * h - 1000, 1000 + 13 * h, 12)) // только что пробовали
    }

    // ---- раздельные настройки ----

    @Test fun settingsKeysScoping() {
        assertEquals("auto_reconnect", SettingsKeys.scopedKey("auto_reconnect", null))
        assertEquals("m_WHITELIST_auto_reconnect", SettingsKeys.scopedKey("auto_reconnect", "WHITELIST"))
        val plan = SettingsKeys.copyPlan(setOf("kill_switch", "run_hour"), "BLACKLIST")
        assertEquals(mapOf("kill_switch" to "m_BLACKLIST_kill_switch"), plan) // общие (расписание) не копируются
        val portable = SettingsKeys.portable(listOf("WHITELIST", "BLACKLIST"))
        assertTrue("sep_WHITELIST" in portable)
        assertTrue("m_BLACKLIST_slow_min_mbps" in portable)
        assertTrue("interval_hours" in portable)
    }

    // ---- прошивки ----

    private fun detect(maker: String, props: Map<String, String>, sdk: Int = 31) =
        FirmwareDetect.detect(maker, maker, "", sdk) { props[it].orEmpty() }

    @Test fun detectsEmui12() {
        val fw = detect("HUAWEI", mapOf("ro.build.version.emui" to "EmotionUI_12.0.0"), 31)
        assertEquals(FirmwareKind.EMUI, fw.kind)
        assertEquals("12.0.0", fw.version)
        assertEquals("EMUI 12.0.0 · HUAWEI · Android 12", fw.text())
    }

    @Test fun detectsOtherFirmwares() {
        assertEquals(FirmwareKind.HYPEROS, detect("Xiaomi", mapOf("ro.mi.os.version.name" to "OS1.0")).kind)
        assertEquals(FirmwareKind.MIUI, detect("Xiaomi", mapOf("ro.miui.ui.version.name" to "V125")).kind)
        assertEquals(FirmwareKind.MAGICOS, detect("HONOR", mapOf("ro.build.version.magic" to "MagicOS_7.0")).kind)
        assertEquals(FirmwareKind.COLOROS, detect("OPPO", mapOf("ro.build.version.opporom" to "V12.1")).kind)
        assertEquals(FirmwareKind.REALME, detect("realme", mapOf("ro.build.version.realmeui" to "V3.0")).kind)
        assertEquals("5.1.0", detect("samsung", mapOf("ro.build.version.oneui" to "50100")).version)
        assertEquals(FirmwareKind.EMUI, detect("huawei", emptyMap()).kind) // свойств нет — по производителю
        assertEquals(FirmwareKind.STOCK, detect("google", emptyMap()).kind)
    }

    @Test fun everyFirmwareHasGuideAndEmuiHasLaunchScreens() {
        for (k in FirmwareKind.entries) assertTrue(FirmwareGuides.forKind(k).steps.isNotEmpty())
        val emui = FirmwareGuides.forKind(FirmwareKind.EMUI)
        assertTrue(emui.actions.any { a -> a.screens.any { it.pkg == "com.huawei.systemmanager" } })
        assertTrue(emui.steps.any { it.contains("Запуск приложений") })
    }

    // ---- ключ reality ----

    @Test fun realityKeyNormalization() {
        assertEquals("ycPIUcY6ci2yi7-_OHc20e4gzxEdJTqpmeShxSuHRU", XrayConfig.realityKey("ycPIUcY6ci2yi7 _OHc20e4gzxEdJTqpmeShxSuHRU"))
        assertEquals("ab-c_d", XrayConfig.realityKey("ab+c/d=="))
        assertEquals("abc-DEF_", XrayConfig.realityKey("abc-DEF_"))
    }

    // ---- DNS через туннель, режим, неподходящие серверы ----

    private val vless = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls&sni=example.com#n"

    @Test fun tunnelDnsGoesThroughCoreOverHttps() {
        val json = XrayConfig.vpnConfig(vless, XrayOptions(ipv6 = Ipv6Mode.BLOCK))
        assertTrue(json.contains("\"protocol\":\"dns\""))
        assertTrue(json.contains("\"outboundTag\":\"dns-out\""))
        assertTrue(json.contains("https://1.1.1.1/dns-query"))
        assertTrue(json.contains("\"queryStrategy\":\"UseIPv4\""))
        assertTrue(XrayConfig.vpnConfig(vless, XrayOptions(ipv6 = Ipv6Mode.TUNNEL)).contains("\"queryStrategy\":\"UseIP\""))
        val plain = XrayConfig.vpnConfig(vless, XrayOptions(secureDns = false))
        assertFalse(plain.contains("dns-out"))
        // у проверочных конфигов DNS прежний
        assertFalse(XrayConfig.probeConfig(vless).contains("dns-query"))
    }

    @Test fun modeChangesAreAnnounced() {
        val dir = java.nio.file.Files.createTempDirectory("mode").toFile()
        val before = Mode.changes.value
        Mode.write(dir, Mode.BLACKLIST)
        assertEquals(before + 1, Mode.changes.value)
        assertEquals(Mode.BLACKLIST, Mode.read(dir))
        dir.deleteRecursively()
    }

    private fun row(name: String, cc: String?) = ConfigRow("vless://u@$name.example.com:443?type=tcp#${java.net.URLEncoder.encode(name, "UTF-8")}", "vless", java.net.URLEncoder.encode(name, "UTF-8"), cc, null, 5)

    @Test fun russianAndPurposeServersAreLastInBlacklistOnly() {
        val rows = listOf(row("Russia 🇷🇺 → [🏛 Госуслуги]", "RU"), row("Auto → [🧲 Торренты]", null), row("poland", "PL"))
        val bl = Ranking.rank(Ranking.candidates(rows, emptyMap(), mode = Mode.BLACKLIST))
        assertTrue(bl.first().link.contains("@poland"))
        assertFalse(bl.first().unfit)
        assertEquals(2, bl.count { it.unfit })
        val wl = Ranking.candidates(rows, emptyMap(), mode = Mode.WHITELIST)
        assertEquals(0, wl.count { it.unfit })
    }

    // ---- свои сервисы и автопроверка ----

    @Test fun customServicesAddNormalizeRemove() {
        val f = File.createTempFile("custom", ".tsv")
        try {
            val cs = CustomServices(f)
            assertEquals("Введите название", cs.add(" ", "discord.com"))
            assertTrue(cs.add("Discord", "не адрес") != null)
            assertEquals(null, cs.add("Discord", "discord.com"))
            assertEquals(null, cs.add("Discovery", "https://discovery.example/x"))
            assertTrue(cs.add("Ещё", "https://discord.com/") != null) // тот же адрес
            val list = cs.load()
            assertEquals(listOf("https://discord.com/", "https://discovery.example/x"), list.map { it.url })
            assertEquals(2, list.map { it.id }.toSet().size) // коды не повторяются
            assertTrue(list.none { it.id.contains(';') || it.id.contains('=') })
            cs.remove(list[0].id)
            assertEquals(1, cs.load().size)
        } finally {
            f.delete()
        }
    }

    @Test fun customIdAvoidsBuiltIn() {
        val id = CustomServices.freeId("Telegram Web", CheckServices.ALL.map { it.id }.toSet())
        assertFalse(id == "TG")
        assertEquals("TE", id)
        assertEquals("YO", CustomServices.freeId("YouTube2", setOf("YT")).let { "YO" })
        assertEquals("TG2", CustomServices.freeId("TG", setOf("TG")))
    }

    @Test fun selectionIncludesCustomAndRespectsFile() {
        val f = File.createTempFile("sel", ".txt")
        try {
            val custom = listOf(CheckService("DI", "Discord", "https://discord.com/"))
            val sel = CheckSelection(f, custom = { custom })
            f.delete()
            assertEquals(CheckServices.ALL.size + 1, sel.selected().size) // по умолчанию всё
            sel.set(listOf("TG", "DI"))
            assertEquals(listOf("TG", "DI"), sel.selected().map { it.id })
        } finally {
            f.delete()
        }
    }

    @Test fun servicePlanPicksAliveWithoutResultsOnePerHost() {
        val today = java.time.LocalDate.now().toString()
        fun c(id: String, host: String) = Candidate("vless://u@$host:443?type=tcp#$id", id, 0, -1.0, 0, alive = true)
        val a = c("a", "h1.example"); val b = c("b", "h1.example"); val d = c("d", "h2.example"); val e = c("e", "h3.example")
        val services = listOf(CheckService("TG", "T", "u"), CheckService("DI", "D", "u"))
        val checks = mapOf(
            CheckStore.keyOf(a.link) to CheckResult(today, 100, mapOf("TG" to 5L)), // нет DI
            CheckStore.keyOf(b.link) to CheckResult(today, 100, emptyMap()),
            CheckStore.keyOf(d.link) to CheckResult(today, 100, mapOf("TG" to 5L, "DI" to -1L)), // всё проверено
            CheckStore.keyOf(e.link) to CheckResult(today, 100, emptyMap()),
        )
        val todo = ServicePlan.pending(listOf(a, b, d, e), checks, services, limit = 10)
        assertEquals(listOf(a.link, e.link), todo.map { it.link }) // d готов, b — тот же сервер, что a
        assertEquals(1, ServicePlan.pending(listOf(a, b, d, e), checks, services, limit = 1).size)
        assertTrue(ServicePlan.pending(listOf(a), checks, emptyList(), 5).isEmpty())
    }

    @Test fun softCheckCountsBotProtectedSiteAsReached() {
        val link = "vless://u@h.example:443?type=tcp&security=tls&sni=h.example#n"
        val checker = ServiceChecker(
            probe = { _, url -> if (url.contains("claude")) -1L else 50L },
            soft = { _, url -> url.contains("claude") },
        )
        val r = checker.checkOne(link, listOf(CheckService("CL", "Claude", "https://claude.ai/"), CheckService("X", "X", "https://x.example/")), "2026-10-17")
        assertEquals(CheckServices.PARTIAL, r.services["CL"])
        val strict = ServiceChecker(probe = { _, url -> if (url.contains("claude")) -1L else 50L }).checkOne(link, listOf(CheckService("CL", "Claude", "https://claude.ai/")), "2026-10-17")
        assertEquals(CheckServices.FAILED, strict.services["CL"])
    }

    // ---- смена сети ----

    @Test fun decisiveDetectionSwitchesAfterOneAnswer() {
        val none = Vote(BlockType.UNKNOWN, 0)
        // обычно нужны два одинаковых определения подряд
        assertEquals(null, ModeDecider.step(none, BlockType.BLACKLIST, Mode.WHITELIST).second)
        // при смене сети хватает одного
        assertEquals(Mode.BLACKLIST, ModeDecider.step(none, BlockType.BLACKLIST, Mode.WHITELIST, need = 1).second)
        // «нет сети» и «не ясно» ничего не меняют
        assertEquals(null, ModeDecider.step(none, BlockType.OFFLINE, Mode.WHITELIST, need = 1).second)
    }

    // ---- убранные сервисы, подбор для цели, быстрый порядок, причины аудита ----

    @Test fun hiddenBuiltInServicesDisappearAndCanReturn() {
        val hf = File.createTempFile("hid", ".txt"); val sf = File.createTempFile("sel", ".txt"); sf.delete()
        try {
            val hidden = HiddenServices(hf)
            val sel = CheckSelection(sf, { listOf(CheckService("DI", "Discord", "https://discord.com/")) }, { hidden.load() })
            assertEquals(CheckServices.ALL.size + 1, sel.all().size)
            hidden.hide("QW"); hidden.hide("ST")
            assertEquals(listOf("TG", "CL", "YT", "DI"), sel.all().map { it.id })
            assertEquals(listOf("TG", "CL", "YT", "DI"), sel.selected().map { it.id })
            hidden.restoreAll()
            assertEquals(CheckServices.ALL.size + 1, sel.all().size)
        } finally {
            hf.delete(); sf.delete()
        }
    }

    @Test fun serviceMatchPutsOpenedFirstFailedLast() {
        fun c(id: String) = Candidate("vless://u@$id.example:443?type=tcp#$id", id, 0, -1.0, 0, alive = true)
        val a = c("a"); val b = c("b"); val d = c("d")
        val yt = CheckServices.ALL.first { it.id == "YT" }
        val checks = mapOf(
            CheckStore.keyOf(a.link) to CheckResult("2026-10-17", 10, mapOf("YT" to -1L)),
            CheckStore.keyOf(d.link) to CheckResult("2026-10-17", 10, mapOf("YT" to 40L)),
        )
        assertEquals(listOf("d", "b", "a"), ServiceMatch.order(listOf(a, b, d), checks, yt).map { it.identity })
        assertEquals(listOf("a", "b", "d"), ServiceMatch.order(listOf(a, b, d), checks, null).map { it.identity })
        assertEquals("YT", ServiceMatch.serviceFor("https://www.youtube.com/generate_204", CheckServices.ALL)?.id)
        assertEquals("CL", ServiceMatch.serviceFor("https://claude.ai/", CheckServices.ALL)?.id)
        assertEquals(null, ServiceMatch.serviceFor("https://example.org/", CheckServices.ALL))
    }

    @Test fun quickOrderPriorityThenLastThenRanked() {
        fun c(host: String, prio: Int = 0, bad: Boolean = false) = Candidate("vless://u@$host:443?type=tcp#$host", host, 0, -1.0, 0, prio = prio, bad = bad)
        val ranked = listOf(c("r1"), c("hi1", prio = 1), c("bad", bad = true), c("r2"), c("hi2", prio = 1), c("r3"))
        val last = "vless://u@last.example:443?type=tcp#last"
        val order = QuickOrder.order(ranked, last, 5).map { it.substringAfter("@").substringBefore(":") }
        assertEquals(listOf("hi1", "hi2", "last.example", "r1", "r2"), order)
        assertEquals(listOf("hi1"), QuickOrder.order(ranked, null, 1).map { it.substringAfter("@").substringBefore(":") })
    }

    @Test fun auditSkipReasons() {
        val p = Proposal("https://a/x", "https://b/y", ProposalKind.entries.first(), 100)
        assertEquals(null, SourceAudit.skipReason(p, emptyList()))
        assertTrue(SourceAudit.skipReason(p, listOf(Blacklist.Entry("https://b/y", ""))) != null) // родитель сам в ЧС
        assertTrue(SourceAudit.skipReason(p, listOf(Blacklist.Entry("https://c/z", "100% включение 100% в https://a/x"))) != null) // на него опираются
        assertEquals("уже в чёрном списке", SourceAudit.skipReason(p, listOf(Blacklist.Entry("https://a/x", ""))))
    }

    @Test fun deadBaselineButServiceOpensIsAlive() {
        val checker = ServiceChecker(
            probe = { _, url -> if (url == CheckServices.BASELINE_URL) -1L else if (url.contains("youtube")) 120L else -1L },
        )
        val yt = CheckServices.ALL.first { it.id == "YT" }
        val cl = CheckServices.ALL.first { it.id == "CL" }
        val r = checker.checkOne("vless://u@h.example:443?type=tcp&security=none#x", listOf(cl, yt), "2026-10-18")
        assertFalse(r.dead)
        assertEquals(120L, r.services["YT"])
        assertEquals(-1L, r.services["CL"])
    }

    @Test fun softAnswerIsPartialAndCapsCountSelectedOnly() {
        val checker = ServiceChecker(probe = { _, url -> if (url == CheckServices.BASELINE_URL) 50L else -1L }, soft = { _, _ -> true })
        val cl = CheckServices.ALL.first { it.id == "CL" }
        val r = checker.checkOne("vless://u@h.example:443?type=tcp&security=none#x", listOf(cl), "2026-10-18")
        assertEquals(CheckServices.PARTIAL, r.services["CL"])
        assertEquals("Claude ≈", r.labelNamed(listOf(cl)))
        assertTrue(CheckServices.opened(r.services["CL"]))
        assertTrue(Soft.regionBlocked("Claude app unavailable in your region"))
        assertFalse(Soft.regionBlocked("Just a moment..."))
    }

    @Test fun oldGenericTargetsGetServiceUrl() {
        val f = File.createTempFile("tgt", ".tsv")
        try {
            f.writeText("APP:ai.qwenlm.chat.android\tAPP\tai.qwenlm.chat.android\tQwen\t${KnownApps.DEFAULT_URL}\tDEFAULT\t2026-10-17\n" +
                "APP:com.android.chrome\tAPP\tcom.android.chrome\tChrome\t${KnownApps.DEFAULT_URL}\tDEFAULT\t2026-10-17\n")
            val t = TargetStore(f).load()
            assertEquals("https://chat.qwen.ai/", t[0].checkUrl)
            assertEquals(KnownApps.DEFAULT_URL, t[1].checkUrl)
        } finally { f.delete() }
    }

    @Test fun russianExitGoesLastForClaudeAndBare403IsNotReached() {
        fun c(id: String, cc: String) = Candidate("vless://u@$id.example:443?type=tcp#$id", id, 0, -1.0, 0, cc = cc)
        val ru = c("ru", "RU"); val nl = c("nl", "NL"); val de = c("de", "DE")
        val cl = CheckServices.ALL.first { it.id == "CL" }
        assertEquals(listOf("nl", "de", "ru"), ServiceMatch.order(listOf(ru, nl, de), emptyMap(), cl).map { it.identity })
        assertEquals(listOf("ru", "nl", "de"), ServiceMatch.order(listOf(ru, nl, de), emptyMap(), CheckServices.ALL.first { it.id == "ST" }.copy(id = "XX")).map { it.identity })
        assertFalse(Soft.accepts(403, "<html>403 Forbidden</html>"))
        assertTrue(Soft.accepts(404, "not found"))
        assertFalse(Soft.accepts(403, "Just a moment... app unavailable in your region"))
    }

    @Test fun verifierKeepsCallerOrder() {
        fun c(id: String, prio: Int) = Candidate("vless://u@$id.example:443?type=tcp#$id", id, 0, -1.0, 0, prio = prio)
        val tried = java.util.Collections.synchronizedList(ArrayList<String>())
        val v = TargetVerifier(probe = { cfg, _ -> tried += cfg; 10L }, parallel = 1, keep = 1)
        val t = Target.app("p", "P", "https://x.example/")
        val got = kotlinx.coroutines.runBlocking { v.verify(t, listOf(c("low", 0), c("high", 5))) }
        assertTrue(got.single().contains("@low"))
    }

    @Test fun measuredResultBeatsCountryGuess() {
        val ru = Candidate("vless://u@ru.example:443?type=tcp#ru", "ru", 0, -1.0, 0, cc = "RU")
        val nl = Candidate("vless://u@nl.example:443?type=tcp#nl", "nl", 0, -1.0, 0, cc = "NL")
        val cl = CheckServices.ALL.first { it.id == "CL" }
        val checks = mapOf(CheckStore.keyOf(ru.link) to CheckResult("2026-10-18", 10, mapOf("CL" to 90L)))
        assertEquals(listOf("ru", "nl"), ServiceMatch.order(listOf(nl, ru), checks, cl).map { it.identity })
    }

    @Test fun claudeThroughRussianExitIsRejected() {
        assertEquals("NL", GeoGate.parseLoc("fl=1\nip=1.2.3.4\nloc=NL\ncolo=AMS\n"))
        assertTrue(GeoGate.blocks("https://claude.ai/", "RU"))
        assertFalse(GeoGate.blocks("https://claude.ai/", "NL"))
        assertFalse(GeoGate.blocks("https://claude.ai/", null))
        assertFalse(GeoGate.blocks("https://www.youtube.com/generate_204", "RU")) // не закрыт по региону
        val cl = CheckServices.ALL.first { it.id == "CL" }
        val yt = CheckServices.ALL.first { it.id == "YT" }
        val link = "vless://u@h.example:443?type=tcp&security=none#x"
        val ru = ServiceChecker(probe = { _, _ -> 50L }, exit = { "RU" }).checkOne(link, listOf(cl, yt), "2026-10-18")
        assertEquals(CheckServices.FAILED, ru.services["CL"])
        assertEquals(50L, ru.services["YT"])
        val nl = ServiceChecker(probe = { _, _ -> 50L }, exit = { "NL" }).checkOne(link, listOf(cl), "2026-10-18")
        assertEquals(50L, nl.services["CL"])
        // цель Claude: сервер с российским выходом отбрасывается, даже если сайт ответил 200
        val v = TargetVerifier(probe = { cfg, _ -> 10L }, parallel = 1, keep = 1, exit = { l -> if (l.contains("@ru")) "RU" else "NL" })
        fun c(h: String) = Candidate("vless://u@$h.example:443?type=tcp&security=none#$h", h, 0, -1.0, 0)
        val got = kotlinx.coroutines.runBlocking { v.verify(Target.app("p", "Claude", "https://claude.ai/"), listOf(c("ru"), c("nl"))) }
        assertTrue(got.single().contains("@nl"))
    }

    @Test fun autoPriorityRanksSourcesRelativelyAndManualWins() {
        val stats = listOf(
            SourceStat("good", 100, 6, 14),   // 30%
            SourceStat("ok", 100, 5, 15),     // 25%
            SourceStat("weak", 100, 1, 19),   // 5%
            SourceStat("dead", 100, 0, 30),
            SourceStat("new", 100, 0, 3),     // мало проверено
        )
        val lv = AutoPriority.levels(stats)
        assertEquals(SourcePriority.HIGH, lv["good"])
        assertEquals(SourcePriority.HIGH, lv["ok"])
        assertEquals(null, lv["weak"])
        assertEquals(SourcePriority.LOW, lv["dead"])
        assertEquals(null, lv["new"])
        assertEquals(emptyMap<String, Int>(), AutoPriority.levels(listOf(SourceStat("x", 10, 0, 2))))
        val eff = AutoPriority.effective(mapOf("dead" to SourcePriority.HIGH), lv)
        assertEquals(SourcePriority.HIGH, eff["dead"])
        assertEquals(SourcePriority.HIGH, eff["good"])
    }

    @Test fun directWatchDropsBlockedAndFindsRussianApps() {
        var s = DirectStat()
        s = s.next(false); assertFalse(s.blocked)
        s = s.next(false); assertTrue(s.blocked)
        s = s.next(true); assertFalse(s.blocked)
        val dir = java.nio.file.Files.createTempDirectory("byp").toFile()
        try {
            val st = BypassStore(dir, Mode.BLACKLIST)
            assertTrue("com.valvesoftware.android.steam.community" in st.effective())
            assertTrue("domain:steampowered.com" in st.siteRules())
            DirectWatch.save(st.watchFile, mapOf("steam" to DirectStat(0, 2), "sber" to DirectStat(5, 0)))
            assertFalse("com.valvesoftware.android.steam.community" in st.effective())
            assertFalse("domain:steampowered.com" in st.siteRules())
            assertTrue("ru.sberbankmobile" in st.effective())
            // найденные на телефоне российские приложения добавляются, прочие нет
            val eff = st.effective(listOf("ru.newbank.app", "com.example.game", "ru.sberbankmobile", "ru.vpnclient.x"))
            assertTrue("ru.newbank.app" in eff)
            assertFalse("com.example.game" in eff)
            assertFalse("ru.vpnclient.x" in eff)
            assertEquals(2, DirectWatch.load(st.watchFile).size)
            assertEquals(DirectStat(0, 2), DirectWatch.load(st.watchFile)["steam"])
        } finally { dir.deleteRecursively() }
    }

    @Test fun diagnosticsFiltersAndCounts() {
        val lines = listOf(
            "2026-10-18T02:20:26.000+03:00 VPN: подключено [x] (режим Белые списки)",
            "2026-10-18T02:21:00.000+03:00 TARGET: «Claude» — рабочих серверов 3, первый [x]",
            "2026-10-18T02:22:00.000+03:00 TARGET: для «Chrome» рабочих серверов не найдено (адрес проверки https://x/)",
            "2026-10-18T02:23:00.000+03:00 A: для «Claude» сервер [x] не отвечает (1 из 2)",
            "2026-10-18T02:24:00.000+03:00 FAILOVER: [x] не работает, ищу среди 10",
            "2026-10-18T02:25:00.000+03:00 MODE: режим «Белые списки» → «Чёрные списки»",
            "2026-10-18T02:26:00.000+03:00 NET: сеть сменилась: wifi → mobile",
        )
        assertEquals("TARGET", Diagnostics.tagOf(lines[1]))
        assertEquals("A", Diagnostics.tagOf(lines[3]))
        assertEquals(2, Diagnostics.filter(lines, "Цели", "", false).size)
        assertEquals(1, Diagnostics.filter(lines, "Цели", "chrome", false).size)
        assertEquals(3, Diagnostics.filter(lines, "Всё", "", true).size) // Chrome не найдено, A не отвечает, FAILOVER не работает
        val c = Diagnostics.counts(lines)
        assertEquals(1, c.failovers); assertEquals(1, c.modeSwitches); assertEquals(1, c.networkChanges)
        assertEquals(1, c.routeFails); assertEquals(1, c.targetUpdates); assertEquals(3, c.problems)
        assertEquals(3, Diagnostics.since(lines, "2026-10-18T02:24:00").size)
        assertEquals("10-18 02:20:26  VPN: подключено [x] (режим Белые списки)", Diagnostics.pretty(lines[0]))
        assertTrue(Diagnostics.bundle(listOf("Режим" to "Белые списки"), c, lines).contains("Режим: Белые списки"))
    }

    @Test fun hongKongIsClosedForClaudeAndOpenAiOnly() {
        assertTrue(GeoGate.blocks("https://claude.ai/", "HK"))
        assertTrue(GeoGate.blocks("https://chatgpt.com/", "MO"))
        assertFalse(GeoGate.blocks("https://gemini.google.com/", "HK"))
        assertTrue(GeoGate.blocks("https://gemini.google.com/", "RU"))
    }

    @Test fun excludedAutoAppsStayOutAndNoneNetworkIsNotRecorded() {
        val dir = java.nio.file.Files.createTempDirectory("byp2").toFile()
        try {
            val st = BypassStore(dir, Mode.BLACKLIST)
            val inst = listOf("ru.zdevs.zarchiver", "ru.newbank.app")
            assertEquals(listOf("ru.newbank.app", "ru.zdevs.zarchiver"), st.discovered(inst))
            st.excludeAuto("ru.zdevs.zarchiver")
            assertEquals(listOf("ru.newbank.app"), st.discovered(inst))
            assertFalse("ru.zdevs.zarchiver" in st.effective(inst))
            assertTrue("ru.newbank.app" in st.effective(inst))
            st.presetEnabled = false
            assertTrue(st.discovered(inst).isEmpty())
        } finally { dir.deleteRecursively() }
        val f = File.createTempFile("hist", ".tsv")
        try {
            val h = ConfigHistory(f)
            h.record("vless|a", "none", true)
            h.record("vless|a", "wifi", true)
            assertEquals(listOf("wifi"), h.placesFor("vless|a").map { it.first })
        } finally { f.delete() }
    }

    @Test
    fun manualModeChoiceHoldsAgainstAutoDetection() {
        val t0 = 1_000_000L
        assertTrue(ModeHold.active(t0, "wifi", t0 + 60_000, "wifi"))
        assertFalse(ModeHold.active(t0, "wifi", t0 + 60_000, "mobile:megafon pre-5g")) // сеть сменилась
        assertFalse(ModeHold.active(t0, "wifi", t0 + ModeHold.HOLD_MS, "wifi")) // срок вышел
        assertFalse(ModeHold.active(0L, "", t0, "wifi")) // вручную не выбирали
    }

    @Test
    fun redactHidesKeysAndSubscriptionTokens() {
        val line = "10-18 08:46:09  OK: https://your-durev.com/sub/AbCdEfGh_0123456789-XyZ (17552 симв.)"
        val r = Diagnostics.redact(line)
        assertFalse(r.contains("AbCdEfGh"))
        assertTrue(r.contains("https://your-durev.com/sub/…"))
        val gh = "OK: https://raw.githubusercontent.com/flaafix/AetrisVPN/refs/heads/main/AetrisVPN.txt (1)"
        assertEquals(gh, Diagnostics.redact(gh)) // обычные пути не трогаем
        assertFalse(Diagnostics.redact("ошибка vless://uuid@1.2.3.4:443?x=y#n конец").contains("uuid@"))
    }
}
