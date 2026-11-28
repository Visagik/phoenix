package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutageEpisodeTest {
    @Test fun startIsKeptAcrossFailures() {
        val e = OutageEpisode()
        assertTrue(e.fail(1_000))
        assertFalse(e.fail(31_000))
        assertFalse(e.fail(61_000))
        assertEquals(1_000L, e.since)
        assertTrue(e.active)
    }

    @Test fun endClosesAndNextFailStartsNew() {
        val e = OutageEpisode()
        e.fail(1_000)
        e.end()
        assertFalse(e.active)
        assertTrue(e.fail(500_000))
        assertEquals(500_000L, e.since)
    }

    @Test fun staleEpisodeRestarts() {
        val e = OutageEpisode()
        e.fail(1_000)
        assertTrue(e.fail(1_000 + OutageEpisode.STALE_MS + 1))
        assertEquals(1_000 + OutageEpisode.STALE_MS + 1, e.since)
    }

    @Test fun deferralDoesNotMoveStart() {
        // защита «не виню» обнуляет счётчик неудач, но не эпизод: начало остаётся первым
        val e = OutageEpisode()
        e.fail(10_000)
        e.fail(40_000)
        e.fail(130_000)
        assertEquals(10_000L, e.since)
        assertEquals(10_000L, OutageStart.pick(150_000L, e.since))
    }
}
