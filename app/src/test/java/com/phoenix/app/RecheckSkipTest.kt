package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecheckSkipTest {
    private fun cand(dead: Boolean, bad: Boolean, steady: Boolean = false, alive: Boolean = false) =
        Candidate(link = "vless://x", identity = "id", days = 1, mbps = -1.0, latencyMs = 0, bad = bad, steady = steady, alive = alive, deadMark = dead)

    @Test fun deadAndBadAreSkipped() {
        assertTrue(RecheckSkip.quarantined(cand(dead = true, bad = true)))
    }

    @Test fun deadOnceOnlyIsStillChecked() {
        assertFalse(RecheckSkip.quarantined(cand(dead = true, bad = false)))
    }

    @Test fun badButNotMarkedDeadIsStillChecked() {
        assertFalse(RecheckSkip.quarantined(cand(dead = false, bad = true)))
    }

    @Test fun steadyIsNeverSkipped() {
        assertFalse(RecheckSkip.quarantined(cand(dead = true, bad = true, steady = true)))
    }

    @Test fun hostStrikesIsThree() {
        assertEquals(3, RecheckSkip.HOST_STRIKES)
        assertEquals(-4L, ServerSearch.HOST_SKIPPED)
    }
}
