package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GovernorRelaxTest {
    private fun gov(): ParallelGovernor {
        val f = File.createTempFile("pgov", ".tsv"); f.delete()
        return ParallelGovernor(f)
    }

    @Test fun levelRelaxesByTimeWithoutHeavyRuns() {
        val g = gov()
        g.begin("n", 1_000_000L)
        assertTrue(g.outage("n", 1_050_000L)) // сбой через 50 с после старта: виновата проверка
        assertEquals(1, g.level("n"))
        g.begin("n", 1_050_000L + 60_000L)
        assertEquals(1, g.level("n"))
        g.begin("n", 1_050_000L + ParallelGovernor.RELAX_MS + 1L)
        assertEquals(0, g.level("n"))
    }

    @Test fun searchAfterFailureDoesNotBlameItself() {
        val g = gov()
        val failAt = 500_000L
        g.begin("n", failAt + 5_000L) // поиск при обрыве начался уже после первого сбоя
        assertEquals(false, g.outage("n", failAt + 20_000L, failAt))
        assertEquals(0, g.level("n"))
    }
}
