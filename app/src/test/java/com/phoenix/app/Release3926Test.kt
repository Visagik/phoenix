package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3926Test {
    private fun gov(): Pair<ParallelGovernor, File> {
        val f = File.createTempFile("pgov", ".tsv"); f.delete()
        return ParallelGovernor(f) to f
    }

    @Test fun defaultsAreFullSpeed() {
        val (g, _) = gov()
        assertEquals(48, g.tcp("wifi:x"))
        assertEquals(12, g.probe("wifi:x", 12))
    }

    @Test fun outageRightAfterHeavyRunLowersLevel() {
        val (g, _) = gov()
        g.begin("n"); g.finish("n", 1_000L)
        assertTrue(g.outage("n", 1_000L + 60_000L))
        assertEquals(24, g.tcp("n"))
        assertEquals(6, g.probe("n", 12))
    }

    @Test fun outageLongAfterIsIgnored() {
        val (g, _) = gov()
        g.begin("n"); g.finish("n", 1_000L)
        assertFalse(g.outage("n", 1_000L + 200_000L))
        assertEquals(0, g.level("n"))
        assertFalse(g.outage("unknown", 5L))
    }

    @Test fun outageDuringRunCounts() {
        val (g, _) = gov()
        g.begin("n")
        assertTrue(g.outage("n", 5L))
        assertEquals(1, g.level("n"))
    }

    @Test fun levelCapsAndProbeFloor() {
        val (g, _) = gov()
        repeat(6) { g.begin("n"); g.outage("n", 1L) }
        assertEquals(3, g.level("n"))
        assertEquals(8, g.tcp("n"))
        assertEquals(2, g.probe("n", 4))
        assertEquals(1, g.probe("n", 1))
    }

    @Test fun cleanRunsRestoreLevel() {
        val (g, _) = gov()
        g.begin("n"); g.outage("n", 1L)
        repeat(4) { g.begin("n"); assertFalse(g.finish("n", 10L)) }
        g.begin("n")
        assertTrue(g.finish("n", 10L))
        assertEquals(0, g.level("n"))
    }

    @Test fun savedAndLoaded() {
        val (g, f) = gov()
        g.begin("net a"); g.outage("net a", 1L); g.save()
        assertEquals(1, ParallelGovernor(f).level("net a"))
        assertEquals(0, ParallelGovernor(f).level("other"))
    }

    @Test fun quarantineReleaseAll() {
        Quarantine.clear()
        Quarantine.add("a", now = 1_000L); Quarantine.add("b", now = 1_000L)
        assertEquals(2, Quarantine.count(now = 2_000L))
        assertEquals(2, Quarantine.releaseAll(now = 2_000L))
        assertFalse(Quarantine.active("a", now = 2_000L))
        assertEquals(0, Quarantine.releaseAll())
    }

    @Test fun probeSplitsBetweenConcurrentRuns() {
        val (g, _) = gov()
        g.begin("n") // перепроверка уже идёт
        g.begin("n") // и поверх неё — поиск при сбое
        assertEquals(2, g.activeCount("n"))
        // 12 делится на 2 одновременных тяжёлых прогона, а не достаётся каждому целиком
        assertEquals(6, g.probe("n", 12))
        // но не ниже пола MIN_PROBE, даже если прогонов много
        assertEquals(2, g.probe("n", 3))
    }

    @Test fun probeNotInflatedWhenAlone() {
        val (g, _) = gov()
        // ни один тяжёлый прогон не идёт (begin ещё не звали) — делить не на что, base=1 остаётся 1
        assertEquals(1, g.probe("n", 1))
    }

    @Test fun paceOnGrid() {
        assertEquals(0L, HealthPace.BASE_MS % HealthPace.GRID_MS)
        assertEquals(0L, HealthPace.CALM_MS % HealthPace.GRID_MS)
        assertEquals(0L, HealthPace.LONG_CALM_MS % HealthPace.GRID_MS)
        assertEquals(0L, HealthPace.SCREEN_OFF_MS % HealthPace.GRID_MS)
    }
}
