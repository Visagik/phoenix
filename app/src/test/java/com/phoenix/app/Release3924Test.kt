package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3924Test {
    private fun tmp() = File.createTempFile("cad", ".tsv").also { it.delete(); it.deleteOnExit() }

    @Test fun lowNeedsThreeDifferentDaysWithoutUpdates() {
        val c = SourceCadence(tmp())
        c.observe("u", setOf("a", "b"), 100)
        assertFalse(c.strike("u", 100)); assertFalse(c.strike("u", 100)) // тот же день — один раз
        assertFalse(c.strike("u", 101))
        assertTrue(c.strike("u", 102))
    }

    @Test fun contentUpdateGivesSecondChance() {
        val c = SourceCadence(tmp())
        c.observe("u", setOf("a"), 100)
        c.strike("u", 100); c.strike("u", 101)
        c.observe("u", setOf("a", "new"), 102) // появились новые конфиги
        assertEquals(0, c.strikes("u"))
        assertFalse(c.strike("u", 102))
    }

    @Test fun recoverResets() {
        val c = SourceCadence(tmp())
        c.strike("u", 1); c.strike("u", 2); c.recover("u")
        assertEquals(0, c.strikes("u"))
    }

    @Test fun cadencePersists() {
        val f = tmp()
        val c = SourceCadence(f)
        c.observe("u", setOf("a"), 5); c.strike("u", 5); c.save()
        assertEquals(1, SourceCadence(f).strikes("u"))
    }

    @Test fun autoPriorityUsesConfirmation() {
        val stats = listOf(SourceStat("dead", 20, 0, 12), SourceStat("good", 20, 10, 2))
        assertEquals(SourcePriority.LOW, AutoPriority.levels(stats)["dead"])
        assertNull(AutoPriority.levels(stats) { false }["dead"])
    }

    @Test fun steadyBeatsPlainWhenAllElseEqual() {
        fun c(i: Int, steady: Boolean) = Candidate("trojan://p@10.0.0.$i:443#S$i", "id$i", 0, -1.0, 0, alive = true, steady = steady)
        assertEquals("id2", Ranking.rank(listOf(c(1, false), c(2, true))).first().identity)
    }

    @Test fun notifyTones() {
        assertEquals(NotifyTone.GREEN, NotifyTone.of(true, false, ""))
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(false, true, ""))
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(true, false, "ищу рабочий сервер среди 40…"))
        assertEquals(NotifyTone.RED, NotifyTone.of(true, false, "нет связи через этот сервер"))
    }

    @Test fun clockSkew() {
        val server = "Sun, 25 Oct 2026 08:00:00 GMT"
        val base = java.time.ZonedDateTime.parse(server, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(5L, ClockSkew.skewSeconds(server, base + 5_000))
        assertFalse(ClockSkew.bad(5))
        assertTrue(ClockSkew.bad(ClockSkew.skewSeconds(server, base + 3 * 3_600_000L)!!))
        assertNull(ClockSkew.skewSeconds("мусор", base))
        assertEquals("отстают на 2 ч", ClockSkew.text(-7_200))
    }
}
