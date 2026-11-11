package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3922Test {
    private fun cand(i: Int, quarantined: Boolean = false) =
        Candidate("trojan://p@10.0.0.$i:443#S$i", "id$i", 0, -1.0, 0, alive = true, quarantined = quarantined)

    @Test fun quarantineLastsThirtyMinutes() {
        Quarantine.clear()
        Quarantine.add("a", now = 1_000L)
        assertTrue(Quarantine.active("a", now = 1_000L + 9 * 60_000L))
        assertFalse(Quarantine.active("a", now = 1_000L + 10 * 60_000L))
        assertFalse(Quarantine.active("a", now = 1_000L + 11 * 60_000L))
        assertFalse(Quarantine.active("never"))
    }

    @Test fun quarantinedGoesAfterOthers() {
        val ranked = Ranking.rank(listOf(cand(1, quarantined = true), cand(2), cand(3)))
        assertEquals("id1", ranked.last().identity)
    }

    @Test fun badStillWorseThanQuarantined() {
        val bad = cand(9).copy(bad = true)
        val ranked = Ranking.rank(listOf(bad, cand(1, quarantined = true), cand(2)))
        assertEquals(listOf("id2", "id1", "id9"), ranked.map { it.identity })
    }

    @Test fun healthPaceBacksOffWhenCalm() {
        assertEquals(30_000L, HealthPace.nextDelay(true, 0, 0))
        assertEquals(60_000L, HealthPace.nextDelay(true, 0, 8))
        assertEquals(90_000L, HealthPace.nextDelay(true, 0, 30))
        assertEquals(30_000L, HealthPace.nextDelay(true, 1, 30))
        assertEquals(120_000L, HealthPace.nextDelay(false, 0, 0))
        assertEquals(30_000L, HealthPace.nextDelay(false, 2, 0))
    }

    @Test fun heavyWorkWaitsForCharger() {
        assertTrue(HeavyGate.allow(manual = false, charging = true, batteryPct = 10, metered = true, hoursSinceLast = 0))
        assertFalse(HeavyGate.allow(manual = false, charging = false, batteryPct = 90, metered = false, hoursSinceLast = 5))
        assertTrue(HeavyGate.allow(manual = true, charging = false, batteryPct = 5, metered = true, hoursSinceLast = 0))
    }

    @Test fun heavyWorkNotStarvedForever() {
        assertTrue(HeavyGate.allow(false, false, 80, false, 25))
        assertFalse(HeavyGate.allow(false, false, 30, false, 25))
        assertFalse(HeavyGate.allow(false, false, 80, true, 25))
    }
}
