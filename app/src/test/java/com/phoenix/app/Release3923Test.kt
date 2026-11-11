package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class Release3923Test {
    private val zone = ZoneId.systemDefault()
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun tmp() = File.createTempFile("act", ".tsv").also { it.delete(); it.deleteOnExit() }

    @Test fun dayParts() {
        assertEquals("ночь", DayPart.of(3)); assertEquals("утро", DayPart.of(6)); assertEquals("день", DayPart.of(12)); assertEquals("вечер", DayPart.of(23))
    }

    @Test fun historyPrefersPartWhenEnoughData() {
        val f = tmp()
        val h = ConfigHistory(f, daypart = true)
        val evening = ms(2026, 10, 20, 20)
        // вечером сервер подводит 4 раза, всего в сети — 6 удач утром
        repeat(6) { h.record("s1", "wifi", true, now = ms(2026, 10, 20, 9) + it) }
        repeat(4) { h.record("s1", "wifi", false, now = evening + it) }
        val v = h.view("wifi", now = evening + 100)("s1")!!
        assertEquals("v=$v all=${h.load()}", 0, v.ok); assertEquals("v=$v", 4, v.fail)
        // утром берётся статистика утра, не вечера
        val m = h.view("wifi", now = ms(2026, 10, 21, 9))("s1")!!
        assertEquals(6, m.ok)
        // днём данных за эту часть суток нет — общий опыт сети
        val d = h.view("wifi", now = ms(2026, 10, 21, 14))("s1")!!
        assertEquals("d=$d all=${h.load()}", 6, d.ok); assertEquals("d=$d", 4, d.fail)
        // в списке «где работал» частей суток нет
        assertEquals(1, h.placesByIdentity()["s1"]!!.size)
    }

    @Test fun historyWithoutDaypartKeepsOneKey() {
        val h = ConfigHistory(tmp(), daypart = false)
        h.record("s", "all", true, now = ms(2026, 10, 20, 20))
        assertEquals(1, h.load().size)
    }

    @Test fun quietNeedsWeekOfData() {
        val a = ActivityHours(tmp(), zone)
        // активность каждый день днём в течение 3 дней: данных мало — тихих часов нет
        for (d in 20..22) a.recordActive(ms(2026, 10, d, 14))
        assertFalse(a.confident(ms(2026, 10, 22, 14)))
        assertFalse(a.isQuietNow(ms(2026, 10, 22, 3)))
    }

    @Test fun quietAtNightAfterWeek() {
        val a = ActivityHours(tmp(), zone)
        for (d in 12..26) for (h in listOf(9, 12, 15, 19)) a.recordActive(ms(2026, 10, d, h))
        val now = ms(2026, 10, 26, 3)
        assertTrue(a.confident(now))
        assertTrue(a.isQuietNow(now))
        assertFalse(a.isQuietNow(ms(2026, 10, 26, 12)))
    }

    @Test fun peakSoonBeforeMorning() {
        val a = ActivityHours(tmp(), zone)
        for (d in 12..26) for (h in listOf(8, 9, 12)) a.recordActive(ms(2026, 10, d, h))
        assertTrue(a.peakSoon(ms(2026, 10, 26, 7, 50)))   // за 10 мин до 8:00
        assertFalse(a.peakSoon(ms(2026, 10, 26, 7, 20)))  // слишком рано
        assertFalse(a.peakSoon(ms(2026, 10, 26, 8, 50)))  // идёт пик, не «перед ним»
    }

    @Test fun activityPersistsAndCountsOncePerDay() {
        val f = tmp()
        val a = ActivityHours(f, zone)
        a.recordActive(ms(2026, 10, 20, 10)); a.recordActive(ms(2026, 10, 20, 10, 30))
        val b = ActivityHours(f, zone)
        val slot = b.slotNow(ms(2026, 10, 20, 10))
        assertEquals(1.0, b.level(slot, ms(2026, 10, 20, 11)), 0.001)
        assertEquals(1, f.readLines().count { it.contains('\t') && !it.startsWith("first") })
    }

    @Test fun manualQuietWindow() {
        assertTrue(QuietWindow.inside(23, 23, 7)); assertTrue(QuietWindow.inside(2, 23, 7)); assertFalse(QuietWindow.inside(7, 23, 7))
        assertTrue(QuietWindow.inside(10, 9, 12)); assertFalse(QuietWindow.inside(12, 9, 12)); assertFalse(QuietWindow.inside(5, 5, 5))
    }

    @Test fun quietBlocksStarveExceptionButNotCharging() {
        assertFalse(HeavyGate.allow(false, false, 90, false, 30, quiet = true))
        assertTrue(HeavyGate.allow(false, true, 90, false, 0, quiet = true))
        assertTrue(HeavyGate.allow(true, false, 5, true, 0, quiet = true))
    }
}
