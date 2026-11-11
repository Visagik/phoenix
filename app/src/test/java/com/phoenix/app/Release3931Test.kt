package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3931Test {
    private fun tmpDir(): File = java.nio.file.Files.createTempDirectory("atom").toFile()

    @Test fun atomicWriteReplacesAndLeavesNoTemp() {
        val d = tmpDir()
        val f = File(d, "x.tsv")
        f.writeTextAtomic("one\n")
        f.writeTextAtomic("two\n")
        assertEquals("two\n", f.readText())
        assertEquals(listOf("x.tsv"), d.list()!!.toList())
        File(d, "sub/y.txt").also { it.writeTextAtomic("a") }
        assertEquals("a", File(d, "sub/y.txt").readText())
    }

    @Test fun cleanupRemovesLeftovers() {
        val d = tmpDir()
        File(d, "a.tsv.123.tmp").writeText("half")
        File(d, "keep.tsv").writeText("ok")
        assertEquals(1, AtomicWrite.cleanup(d))
        assertTrue(File(d, "keep.tsv").exists())
    }

    @Test fun crashLogRoundTrip() {
        val d = tmpDir()
        assertNull(CrashLog.take(d))
        val e = IllegalStateException("boom", RuntimeException("cause"))
        CrashLog.record(d, CrashLog.format("main", e, "2026-10-27T10:00:00Z"))
        CrashLog.record(d, CrashLog.format("pool-1", e, "2026-10-27T10:01:00Z"))
        val t = CrashLog.take(d)!!
        assertTrue("boom" in t && "cause" in t && "поток=main" in t && "поток=pool-1" in t)
        assertNull(CrashLog.take(d)) // забрали — файла нет
        val lines = CrashLog.logLines(t, 5)
        assertEquals(5, lines.size)
        assertTrue(lines.all { it.startsWith("CRASH: ") })
    }

    @Test fun crashLogCapped() {
        val d = tmpDir()
        repeat(10) { CrashLog.record(d, "x".repeat(5_000)) }
        assertTrue(File(d, CrashLog.FILE).length() <= CrashLog.MAX_CHARS)
    }

    private fun cand(link: String, cc: String = "") = Candidate(link, ConfigIdentity.key(link), 0, -1.0, 0, cc = cc)
    private val tg = CheckService("TG", "Telegram", "https://web.telegram.org/")

    private fun checks(vararg p: Pair<Candidate, Long>): Map<String, CheckResult> =
        p.associate { (c, ms) -> CheckStore.keyOf(c.link) to CheckResult("2026-10-27", 100, mapOf("TG" to ms)) }

    @Test fun strictDropsBlockedWhenEnoughGood() {
        val good = (1..3).map { cand("trojan://pw@10.0.0.$it:443#Good Server $it") }
        val bad = cand("trojan://pw@10.0.1.1:443#Bad Server one")
        val unknown = cand("trojan://pw@10.0.2.1:443#Unknown Server")
        val ck = checks(*good.map { it to 120L }.toTypedArray(), bad to -1L)
        val out = ServiceMatch.strict(listOf(bad) + good + unknown, ck, tg)
        assertFalse(bad in out)
        assertTrue(unknown in out)
        assertEquals(4, out.size)
    }

    @Test fun strictKeepsEverythingWhenFewGood() {
        val good = cand("trojan://pw@10.0.0.1:443#Good Server 1")
        val bad = cand("trojan://pw@10.0.1.1:443#Bad Server one")
        val ck = checks(good to 100L, bad to -1L)
        val pool = listOf(bad, good)
        assertEquals(pool, ServiceMatch.strict(pool, ck, tg))
        assertEquals(pool, ServiceMatch.strict(pool, ck, null))
    }

    @Test fun strictDropsGeoBlockedUnmeasured() {
        val good = (1..3).map { cand("trojan://pw@10.0.0.$it:443#Good Server $it") }
        val ru = cand("trojan://pw@10.0.3.1:443#RU Server", cc = "RU")
        val ck = checks(*good.map { it to 90L }.toTypedArray())
        val out = ServiceMatch.strict(listOf(ru) + good, ck, tg)
        // группа 4 только если в ServiceBlocks RU закрыт для TG; иначе остаётся как «не проверялся»
        val blocked = ServiceBlocks.status("RU")[ServiceBlocks.LABELS.indexOf("TG")] == "blocked"
        assertEquals(!blocked, ru in out)
    }

    @Test fun fingerprintFindsLikelyRepeats() {
        val a = "vless://uuid-1@a.example.com:443?security=reality&pbk=KEY1&sni=x.com&type=tcp#One"
        val b = "vless://uuid-1@1.2.3.4:443?security=reality&pbk=KEY1&sni=x.com&type=tcp#Two"
        val c = "vless://uuid-2@b.example.com:443?security=reality&pbk=KEY2&sni=x.com&type=tcp#Three"
        assertEquals(ServerFingerprint.of(a), ServerFingerprint.of(b))
        assertFalse(ServerFingerprint.of(a) == ServerFingerprint.of(c))
        val groups = ServerFingerprint.likelyRepeats(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
        assertNull(ServerFingerprint.of("vmess://eyJhZGQiOiJ4In0="))
        assertNull(ServerFingerprint.of("garbage"))
    }

    @Test fun fingerprintSameAddressIsNotRepeat() {
        val a = "trojan://pw@h.example.com:443?sni=a#X"
        val b = "trojan://pw@h.example.com:443?sni=a#Y"
        assertTrue(ServerFingerprint.likelyRepeats(listOf(a, b)).isEmpty())
    }

    @Test fun exitReasonsDescribeAndNotable() {
        assertTrue("ПАДЕНИЕ" in ExitReasons.reason(4))
        assertTrue(ExitReasons.notable(4) && ExitReasons.notable(3) && ExitReasons.notable(13))
        assertFalse(ExitReasons.notable(10) || ExitReasons.notable(16) || ExitReasons.notable(1))
        assertTrue(ExitReasons.memoryLimiter("MemoryLimiter:AnonSwap"))
        val l = ExitReasons.line(13, "MemoryLimiter:AnonSwap", 125, 512_000)
        assertTrue("ЛИМИТ ПАМЯТИ" in l && "500 МБ" in l && l.startsWith("EXIT: "))
        assertEquals("код 99", ExitReasons.reason(99))
    }

    @Test fun customRomOnXiaomiIsNotMiui() {
        assertEquals(OemGuide.Vendor.OTHER, OemGuide.vendorFor("Xiaomi", "Redmi", FirmwareKind.STOCK))
        assertEquals(OemGuide.Vendor.XIAOMI, OemGuide.vendorFor("Xiaomi", "Redmi", FirmwareKind.MIUI))
        assertEquals(OemGuide.Vendor.XIAOMI, OemGuide.vendorFor("Xiaomi", "Redmi", FirmwareKind.HYPEROS))
        assertEquals(OemGuide.Vendor.VIVO, OemGuide.vendorFor("vivo", "vivo", FirmwareKind.STOCK))
    }

    @Test fun android17Named() {
        assertTrue("Android 17" in FirmwareInfo(FirmwareKind.STOCK, "", "Xiaomi", 37).text())
    }

    @Test fun undoRestoresFileAndExpires() {
        val d = tmpDir()
        val f = File(d, "blacklist.txt")
        f.writeText("a\n")
        val u = UndoStore(File(d, "undo"))
        val id = u.snapshot("Аудит", listOf(f), 1_000L)
        f.writeText("a\nb\n")
        assertEquals(1, u.available(2_000L).size)
        assertEquals("Аудит", u.available(2_000L)[0].label)
        assertTrue(u.restore(id, 2_000L))
        assertEquals("a\n", f.readText())
        assertTrue(u.available(2_000L).isEmpty()) // запись использована
        assertFalse(u.restore(id, 2_000L))
    }

    @Test fun undoDeletesFileThatDidNotExist() {
        val d = tmpDir()
        val f = File(d, "prio.tsv")
        val u = UndoStore(File(d, "undo"))
        val id = u.snapshot("Авто-приоритеты", listOf(f), 5_000L)
        f.writeText("x")
        assertTrue(u.restore(id, 6_000L))
        assertFalse(f.exists())
    }

    @Test fun undoExpiresAfterDayAndKeepsFew() {
        val d = tmpDir()
        val f = File(d, "f.txt").also { it.writeText("1") }
        val u = UndoStore(File(d, "undo"))
        val id = u.snapshot("старое", listOf(f), 1_000L)
        assertTrue(u.available(1_000L + UndoStore.TTL_MS + 1).isEmpty())
        assertFalse(u.restore(id, 1_000L + UndoStore.TTL_MS + 1))
        val u2 = UndoStore(File(d, "undo2"))
        repeat(7) { u2.snapshot("s$it", listOf(f), 10_000L + it) }
        assertEquals(UndoStore.KEEP, u2.available(20_000L).size)
        assertEquals("s6", u2.available(20_000L)[0].label)
    }

    @Test fun overriddenSettingsListed() {
        val all = mapOf<String, Any?>(
            "kill_switch" to true, "m_BLACKLIST_kill_switch" to false, // отличается
            "slow_switch" to true, "m_BLACKLIST_slow_switch" to true, // то же значение
            "block_quic" to true, "m_BLACKLIST_block_quic" to false,
            "m_WHITELIST_kill_switch" to false,
            "auto_connect" to true,
        )
        assertEquals(listOf("kill_switch", "block_quic"), SettingsKeys.overridden(all, "BLACKLIST"))
        assertEquals(listOf("kill_switch"), SettingsKeys.overridden(all, "WHITELIST"))
        val present = all.keys
        assertEquals(setOf("m_BLACKLIST_kill_switch", "m_BLACKLIST_slow_switch", "m_BLACKLIST_block_quic"), SettingsKeys.resetKeys(present, "BLACKLIST").toSet())
        assertTrue(SettingsKeys.SCOPED.keys.all { it in SettingsKeys.LABELS })
    }

    @Test fun serverWhyReasons() {
        val c = Candidate("trojan://pw@1.1.1.1:443#Alpha Server", "id", 0, 8.4, 85, rel = 0.92, steady = true, alive = true, protoBucket = 0)
        val svc = listOf(CheckService("TG", "Telegram", "https://t.me"), CheckService("YT", "YouTube", "https://youtube.com"))
        val r = ServerWhy.explain(c, svc, CheckResult("2026-10-27", 85, mapOf("TG" to 120L, "YT" to -1L)), exitChecked = true)
        assertTrue(r.pros.any { "8.4 Мбит/с" in it } && r.pros.any { "Telegram" in it } && r.pros.any { "стабилен" in it })
        assertTrue(r.cons.any { "YouTube" in it })
        assertTrue("＋" in r.text && "－" in r.text)
        val bad = ServerWhy.explain(Candidate("trojan://pw@2.2.2.2:443#B", "id2", 0, -1.0, 0, bad = true, quarantined = true, rel = 0.2))
        assertTrue(bad.cons.size >= 3 && bad.pros.isEmpty())
    }
}
