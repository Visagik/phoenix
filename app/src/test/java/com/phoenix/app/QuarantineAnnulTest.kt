package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineAnnulTest {
    @Test fun annulRemovesOnlyStrikesSinceAndKeepsOlder() {
        Quarantine.clear()
        val t0 = 1_000_000_000L
        Quarantine.add("old", t0)
        Quarantine.add("new", t0 + 130_000)
        Quarantine.add("both", t0)
        Quarantine.add("both", t0 + 130_000)
        val n = Quarantine.annulSince(t0 + 50_000, t0 + 140_000)
        assertEquals(2, n)
        assertTrue(Quarantine.active("old", t0 + 140_000))
        assertFalse(Quarantine.active("new", t0 + 140_000))
        assertTrue(Quarantine.active("both", t0 + 140_000)) // первый провал остался
        Quarantine.clear()
    }

    @Test fun annulDropsLongQuarantineWhenStrikesFallBelowThree() {
        Quarantine.clear()
        val t0 = 1_000_000_000L
        Quarantine.add("s", t0); Quarantine.add("s", t0 + 130_000); Quarantine.add("s", t0 + 260_000)
        assertTrue(Quarantine.isLong("s", t0 + 260_001))
        Quarantine.annulSince(t0 + 100_000, t0 + 260_002)
        assertFalse(Quarantine.isLong("s", t0 + 260_003))
        Quarantine.clear()
    }

    @Test fun annulWithZeroSinceDoesNothing() {
        Quarantine.clear()
        Quarantine.add("x", 5_000_000_000L)
        assertEquals(0, Quarantine.annulSince(0L))
        Quarantine.clear()
    }
}
