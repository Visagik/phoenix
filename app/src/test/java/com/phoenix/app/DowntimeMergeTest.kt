package com.phoenix.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DowntimeMergeTest {
    private fun dir(): File = java.nio.file.Files.createTempDirectory("dt").toFile()

    @Test fun sameDayAndKindAreSummedMaxIsMax() {
        val d = dir()
        val wl = File(d, "downtime.tsv"); val bl = File(d, "bl.tsv")
        wl.writeText("2026-11-28\tMODE\t2\t100\t70\n")
        bl.writeText("2026-11-28\tMODE\t1\t30\t30\n2026-11-28\tFAILOVER\t1\t10\t10\n")
        val s = DowntimeStats(wl)
        s.absorb(listOf(bl))
        assertEquals("перерывов 4, всего 2 мин 20 с, самый долгий 70 с (смена режима 3, смена сервера 1)", s.summary("2026-11-28"))
        assertFalse(bl.exists())
    }

    @Test fun differentDaysAndEmptyAndBrokenLines() {
        val d = dir()
        val main = File(d, "downtime.tsv"); val a = File(d, "a.tsv"); val b = File(d, "b.tsv"); val missing = File(d, "none.tsv")
        main.writeText("2026-11-27\tNET\t1\t5\t5\n")
        a.writeText("мусор\n2026-11-28\tLOSS\tx\t1\t1\n2026-11-28\tLOSS\t2\t20\t12\n")
        b.writeText("")
        val s = DowntimeStats(main)
        s.absorb(listOf(a, b, missing))
        assertEquals("перерывов 1, всего 0 мин 5 с, самый долгий 5 с (смена сети 1)", s.summary("2026-11-27"))
        assertEquals("перерывов 2, всего 0 мин 20 с, самый долгий 12 с (сбой связи 2)", s.summary("2026-11-28"))
        assertFalse(a.exists()); assertFalse(b.exists())
    }

    @Test fun secondAbsorbChangesNothingAndCounterGrowsAcrossModes() {
        val d = dir()
        val main = File(d, "downtime.tsv"); val bl = File(d, "bl.tsv")
        bl.writeText("2026-11-28\tMODE\t3\t60\t30\n")
        val s = DowntimeStats(main)
        s.absorb(listOf(bl)); s.absorb(listOf(bl))
        assertEquals("перерывов 3, всего 1 мин 0 с, самый долгий 30 с (смена режима 3)", s.summary("2026-11-28"))
        assertTrue(s.add(DowntimeStats.MODE, 27, "2026-11-28")) // «за сегодня» после смены режима только растёт
        assertEquals("перерывов 4, всего 1 мин 27 с, самый долгий 30 с (смена режима 4)", s.summary("2026-11-28"))
    }

    @Test fun sameFileIsNotMergedIntoItself() {
        val d = dir(); val f = File(d, "downtime.tsv"); f.writeText("2026-11-28\tMODE\t1\t10\t10\n")
        val s = DowntimeStats(f); s.absorb(listOf(f))
        assertTrue(f.exists())
        assertEquals("перерывов 1, всего 0 мин 10 с, самый долгий 10 с (смена режима 1)", s.summary("2026-11-28"))
    }
}
