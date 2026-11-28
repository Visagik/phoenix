package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiteProfileTest {
    @Test fun notLiteChangesNothing() {
        assertEquals(16, LiteProfile.probeCap(16, lite = false))
        assertEquals(48, LiteProfile.tcpCap(48, lite = false))
        assertEquals(3_000_000, LiteProfile.bodyChars(3_000_000, lite = false))
        assertEquals(200, LiteProfile.memHigh(200, lite = false))
        assertEquals(260, LiteProfile.memCritical(260, lite = false))
        val big = (1..2000).map { "vless://$it" }
        assertTrue(LiteProfile.capRecheck(big, lite = false) === big)
        assertTrue(LiteProfile.capSource(big, lite = false) === big)
        assertTrue(LiteProfile.capTotal(big, lite = false) === big)
        assertEquals(Mode.WHITELIST, LiteProfile.mode(Mode.WHITELIST, lite = false))
        assertEquals(true, LiteProfile.autoMode(true, lite = false))
        assertEquals(true, LiteProfile.autoServiceCheck(true, lite = false))
        assertEquals(true, LiteProfile.appAwareFailover(true, lite = false))
        assertEquals(true, LiteProfile.hevEngine(true, lite = false))
        assertTrue(LiteProfile.skipModes(lite = false).isEmpty())
        assertEquals(true, LiteProfile.defaultAnimations(lite = false))
        assertEquals(true, LiteProfile.defaultSlowSwitch(lite = false))
        assertEquals(false, LiteProfile.defaultOnlyCharging(false, lite = false))
        assertEquals(1, LiteProfile.defaultRecheckSpeed(1, 0, lite = false))
        assertEquals(null, LiteProfile.startLine(lite = false))
    }

    @Test fun liteLimitsOnlyLower() {
        assertEquals(2, LiteProfile.probeCap(16, lite = true))
        assertEquals(1, LiteProfile.probeCap(1, lite = true)) // ниже предела не поднимаем
        assertEquals(8, LiteProfile.tcpCap(48, lite = true))
        assertEquals(5, LiteProfile.tcpCap(5, lite = true))
        assertEquals(600_000, LiteProfile.bodyChars(3_000_000, lite = true))
        assertEquals(120, LiteProfile.memHigh(200, lite = true))
        assertEquals(170, LiteProfile.memCritical(260, lite = true))
    }

    @Test fun recheckKeepsFirstByRankInOrder() {
        val ranked = (1..500).toList()
        val cut = LiteProfile.capRecheck(ranked, lite = true)
        assertEquals(LiteProfile.RECHECK_MAX, cut.size)
        assertEquals((1..LiteProfile.RECHECK_MAX).toList(), cut)
        assertEquals(listOf(1, 2, 3), LiteProfile.capRecheck(listOf(1, 2, 3), lite = true))
    }

    @Test fun capTotalKeepsFirst() {
        val items = (1..2310).toList()
        val cut = LiteProfile.capTotal(items, lite = true)
        assertEquals(800, cut.size)
        assertEquals(items.take(800), cut)
    }

    @Test fun capSourceCountsLinksNotComments() {
        val lines = ArrayList<String>()
        lines += "# заголовок"
        for (i in 1..1240) { lines += "vless://x$i"; if (i % 100 == 0) lines += "# комментарий" }
        val cut = LiteProfile.capSource(lines, lite = true)
        assertEquals(300, LiteProfile.linkCount(cut))
        assertEquals(lines.subList(0, cut.size), cut) // порядок и начало сохранены
        assertEquals(1240, LiteProfile.linkCount(lines))
        val small = listOf("# a", "vless://1", "vless://2")
        assertTrue(LiteProfile.capSource(small, lite = true) === small)
    }

    @Test fun liteForcesFixedSettings() {
        assertEquals(Mode.BLACKLIST, LiteProfile.mode(Mode.WHITELIST, lite = true))
        assertEquals(false, LiteProfile.autoMode(true, lite = true))
        assertEquals(false, LiteProfile.autoServiceCheck(true, lite = true))
        assertEquals(false, LiteProfile.appAwareFailover(true, lite = true))
        assertEquals(false, LiteProfile.hevEngine(true, lite = true)) // в Lite hev выключен всегда
        assertEquals(false, LiteProfile.hevEngine(false, lite = false))
        assertEquals(setOf(Mode.WHITELIST), LiteProfile.skipModes(lite = true))
    }

    @Test fun liteDefaults() {
        assertEquals(false, LiteProfile.defaultAnimations(lite = true))
        assertEquals(false, LiteProfile.defaultSlowSwitch(lite = true))
        assertEquals(true, LiteProfile.defaultOnlyCharging(false, lite = true))
        assertEquals(0, LiteProfile.defaultRecheckSpeed(1, 0, lite = true))
        assertTrue(LiteProfile.startLine(lite = true)!!.startsWith("LITE: проб 2, TCP 8, перепроверка 200, сбор 300/800, файл до 600 тыс. символов, память 120/170 МБ"))
    }

    // ---- К3: общий предел с учётом важности ----
    private data class Item(val id: Int, val cur: Boolean = false, val live: Boolean = false, val prio: Int = SourcePriority.NORMAL)

    private fun rank(i: Item) = LiteProfile.keepRank(i.cur, i.live, i.prio)

    @Test fun rankedCapKeepsLiveFromTheEndOfList() {
        val items = (1..900).map { Item(it) } + (901..950).map { Item(it, live = true) } // живые стоят после 900 непроверенных
        val kept = LiteProfile.capTotalRanked(items, ::rank, lite = true)
        assertEquals(800, kept.size)
        assertTrue((901..950).all { id -> kept.any { it.id == id } })
    }

    @Test fun rankedCapAlwaysKeepsCurrentServer() {
        val items = (1..2000).map { Item(it, cur = it == 1999) }
        val kept = LiteProfile.capTotalRanked(items, ::rank, lite = true)
        assertTrue(kept.any { it.id == 1999 })
    }

    @Test fun rankedCapPrefersHighPrioritySource() {
        val items = (1..1000).map { Item(it) } + (1001..1100).map { Item(it, prio = SourcePriority.HIGH) } + (1101..1500).map { Item(it, prio = SourcePriority.LOW) }
        val kept = LiteProfile.capTotalRanked(items, ::rank, lite = true)
        assertTrue((1001..1100).all { id -> kept.any { it.id == id } })
        assertTrue(kept.none { it.prio == SourcePriority.LOW }) // низкие — в самый конец
        assertEquals(800, kept.size)
    }

    @Test fun rankedCapKeepsOriginalOrder() {
        val items = (1..1200).map { Item(it, live = it % 7 == 0) }
        val kept = LiteProfile.capTotalRanked(items, ::rank, lite = true)
        assertEquals(kept.map { it.id }.sorted(), kept.map { it.id })
        assertTrue(items.filter { it.live }.all { l -> kept.any { it.id == l.id } })
    }

    @Test fun rankedCapUnchangedWhenNotLiteOrSmall() {
        val items = (1..1200).map { Item(it) }
        assertTrue(LiteProfile.capTotalRanked(items, ::rank, lite = false) === items)
        val small = (1..100).map { Item(it) }
        assertTrue(LiteProfile.capTotalRanked(small, ::rank, lite = true) === small)
    }

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("lite4").toFile()

    @Test fun backupImportSkipsWhitelistDataAndKeepsBlacklist() {
        val a = tmp()
        Mode.WHITELIST.dir(a); Mode.BLACKLIST.dir(a)
        File(Mode.WHITELIST.dir(a), "blacklist.txt").writeText("w.example\n")
        File(Mode.BLACKLIST.dir(a), "blacklist.txt").writeText("b.example\n")
        val text = Backup.export(a, emptyMap(), "s")
        val b = tmp()
        val r = Backup.import(b, text, skip = setOf(Mode.WHITELIST))
        assertTrue(r.notes.any { it.contains("пропущены") })
        assertTrue(!File(Mode.WHITELIST.dir(b), "blacklist.txt").exists())
        assertEquals("b.example", File(Mode.BLACKLIST.dir(b), "blacklist.txt").readText().trim())
        // без пропуска (телефон) данные принимаются
        val c = tmp()
        Backup.import(c, text)
        assertEquals("w.example", File(Mode.WHITELIST.dir(c), "blacklist.txt").readText().trim())
    }
}
