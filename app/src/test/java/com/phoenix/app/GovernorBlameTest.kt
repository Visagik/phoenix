package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GovernorBlameTest {
    private fun gov(): ParallelGovernor {
        val f = File.createTempFile("pgov", ".tsv"); f.delete()
        return ParallelGovernor(f)
    }

    @Test fun outageThatStartedBeforeTheRunIsNotBlamedOnIt() {
        val g = gov()
        g.begin("n", 100_000L)
        // сбой связи начался за 13 с до старта проверки (журнал 21:28:26 и 21:28:39)
        assertFalse(g.outage("n", 101_000L, startedAt = 87_000L))
        assertEquals(0, g.level("n"))
    }

    @Test fun outageLongAfterStartIsBlamed() {
        val g = gov()
        g.begin("n", 100_000L)
        assertTrue(g.outage("n", 160_000L, startedAt = 140_000L))
        assertEquals(1, g.level("n"))
    }

    @Test fun maxLevelLimitsTheCut() {
        val g = gov()
        repeat(2) { g.begin("n", 100_000L); g.outage("n", 160_000L, startedAt = 140_000L) }
        assertEquals(2, g.level("n"))
        assertEquals(12, g.tcp("n"))
        assertEquals(24, g.tcp("n", 1))
        assertEquals(48, g.tcp("n", 0))
        assertEquals(4, g.probe("n", 16))
        assertEquals(8, g.probe("n", 16, 1))
        assertEquals(16, g.probe("n", 16, 0))
    }
}
