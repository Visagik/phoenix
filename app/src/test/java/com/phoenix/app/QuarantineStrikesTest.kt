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
        repeat(3) { Quarantine.add("long", now = it * min) }
        assertEquals(1, Quarantine.releaseAll(now = 5 * min))
        assertFalse(Quarantine.active("short", now = 5 * min))
        assertTrue(Quarantine.active("long", now = 5 * min))
    }

    @Test fun forgiveClearsStrikes() {
        Quarantine.resetForTest()
        Quarantine.add("a", now = 0)
        Quarantine.add("a", now = min)
        Quarantine.forgive("a")
        Quarantine.add("a", now = 2 * min)
        assertFalse(Quarantine.isLong("a", now = 3 * min))
        assertTrue(Quarantine.active("a", now = 3 * min))
    }

    @Test fun survivesRestartThroughFile() {
        Quarantine.resetForTest()
        val f = java.io.File.createTempFile("quarantine", ".txt")
        try {
            Quarantine.attach(f, now = 0)
            repeat(3) { Quarantine.add("srv|1", now = it * min) }
            Quarantine.add("other", now = 3 * min)
            val saved = f.readText()
            Quarantine.resetForTest()
            assertFalse(Quarantine.active("srv|1", now = 4 * min))
            Quarantine.restore(saved, now = 4 * min)
            assertTrue(Quarantine.isLong("srv|1", now = 4 * min))
            assertTrue(Quarantine.active("other", now = 4 * min))
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
