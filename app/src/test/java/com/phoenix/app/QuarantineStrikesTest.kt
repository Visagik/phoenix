package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineStrikesTest {
    private val min = 60_000L
    private val hour = 60 * min

    @Test fun firstFailureIsShort() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0)
        assertTrue(Quarantine.active("a", now = 5 * min))
        assertFalse(Quarantine.isLong("a", now = 5 * min))
        assertFalse(Quarantine.active("a", now = 11 * min))
    }

    @Test fun thirdFailureInADayIsLong() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0)
        Quarantine.add("a", now = 20 * min)
        Quarantine.add("a", now = 40 * min)
        assertTrue(Quarantine.isLong("a", now = 41 * min))
        assertTrue(Quarantine.active("a", now = 23 * hour))
        assertFalse(Quarantine.active("a", now = 25 * hour))
    }

    @Test fun failuresSpreadOverMoreThanADayDoNotAccumulate() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0)
        Quarantine.add("a", now = 12 * hour)
        Quarantine.add("a", now = 30 * hour)
        assertFalse(Quarantine.isLong("a", now = 30 * hour + min))
    }

    @Test fun deadNetworkIsNotTheServersFault() {
        Quarantine.resetForTest()
        repeat(5) { Quarantine.add("a", now = it * min, networkAlive = false) }
        assertFalse(Quarantine.active("a", now = 6 * min))
    }

    @Test fun releaseAllKeepsLongQuarantine() {
        Quarantine.resetForTest()
        Quarantine.add("short", now = 0)
        repeat(3) { Quarantine.add("long", now = it * 3 * min) }
        assertEquals(1, Quarantine.releaseAll(now = 7 * min))
        assertFalse(Quarantine.active("short", now = 7 * min))
        assertTrue(Quarantine.active("long", now = 7 * min))
    }

    @Test fun forgiveRemovesOnlyOneStrike() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0)
        Quarantine.add("a", now = 5 * min)
        Quarantine.forgive("a")
        assertFalse(Quarantine.active("a", now = 6 * min)) // короткий карантин снят
        Quarantine.add("a", now = 10 * min)
        assertFalse(Quarantine.isLong("a", now = 11 * min)) // один страйк остался и ещё один: пока два
        Quarantine.add("a", now = 15 * min)
        assertTrue(Quarantine.isLong("a", now = 16 * min)) // третий: суточный
    }

    @Test fun alternatingServerEventuallyGetsLongQuarantine() {
        Quarantine.resetForTest()
        // работает, падает, работает, падает...: удача снимает один страйк, но падений больше, чем удач
        repeat(5) { i ->
            Quarantine.add("a", now = i * 10 * min)
            if (i % 2 == 1) Quarantine.forgive("a")
        }
        assertTrue(Quarantine.isLong("a", now = 41 * min))
    }

    @Test fun sameEpisodeCountsOneStrike() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0, earlyFall = true)
        Quarantine.add("a", now = 2_000) // перебор винит тот же обрыв
        Quarantine.add("a", now = 4_000)
        assertFalse(Quarantine.isLong("a", now = 5_000))
        Quarantine.add("a", now = 10 * min)
        assertFalse(Quarantine.isLong("a", now = 10 * min + 1)) // вторая отдельная неисправность, не третья
    }

    @Test fun earlyFallHourIsNotShortenedBySecondCall() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0, earlyFall = true)
        Quarantine.add("a", now = 3_000) // без earlyFall: срок не сокращается
        assertTrue(Quarantine.active("a", now = 59 * min))
        assertFalse(Quarantine.active("a", now = 61 * min))
    }

    @Test fun skipHasNoStrike() {
        Quarantine.resetForTest()
        repeat(5) { Quarantine.skip("a", now = it * 20 * min) }
        assertFalse(Quarantine.isLong("a", now = 100 * min))
        Quarantine.skip("b", now = 0)
        assertTrue(Quarantine.active("b", now = 5 * min))
        assertFalse(Quarantine.active("b", now = 11 * min))
    }

    @Test fun survivesRestartThroughFile() {
        Quarantine.resetForTest()
        val f = java.io.File.createTempFile("quarantine", ".txt")
        try {
            Quarantine.attach(f, now = 0)
            repeat(3) { Quarantine.add("srv|1", now = it * 3 * min) }
            Quarantine.add("other", now = 9 * min)
            val saved = f.readText()
            Quarantine.resetForTest()
            assertFalse(Quarantine.active("srv|1", now = 10 * min))
            Quarantine.restore(saved, now = 10 * min)
            assertTrue(Quarantine.isLong("srv|1", now = 10 * min))
            assertTrue(Quarantine.active("other", now = 10 * min))
            assertFalse(Quarantine.active("other", now = 20 * min))
        } finally {
            f.delete()
        }
    }

    @Test fun expiredEntriesAreNotRestored() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0)
        val text = Quarantine.serialize(now = 0)
        Quarantine.resetForTest()
        Quarantine.restore(text, now = 2 * hour)
        assertFalse(Quarantine.active("a", now = 2 * hour))
    }
}
