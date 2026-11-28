package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBenchTest {
    private fun ids(prefix: String, n: Int) = (1..n).map { "$prefix$it" }.toSet()

    @Test fun bigSourceIsOverLimit() {
        val m = mapOf("a" to ids("a", 501), "b" to ids("b", 500), "c" to ids("c", 10))
        assertEquals(setOf("a"), SourceBench.bigUrls(m, emptySet()))
    }

    @Test fun ownSourceIsNeverBenched() {
        val m = mapOf("mine" to ids("m", 800))
        assertTrue(SourceBench.bigUrls(m, setOf("mine")).isEmpty())
        assertTrue(SourceBench.benchIds(m, setOf("mine")).isEmpty())
    }

    @Test fun configAlsoInSmallSourceStaysInMain() {
        val m = mapOf("big" to ids("x", 600), "small" to setOf("x1", "x2"))
        val bench = SourceBench.benchIds(m, emptySet())
        assertEquals(598, bench.size)
        assertFalse("x1" in bench)
        assertTrue("x3" in bench)
    }

    private fun target(label: String, servers: List<String>) = Target("id$label", TargetKind.APP, "pkg$label", label, "https://x/", servers = servers)

    @Test fun noReasonWhenEnough() = assertEquals(null, SourceBench.reason(200, 10, emptyList()))

    @Test fun reasonWhenTargetsShort() {
        val r = SourceBench.reason(200, 10, listOf("Claude"))
        assertTrue(r!!.contains("Claude"))
    }

    @Test fun reasonWhenAliveBelowNeed() = assertTrue(SourceBench.reason(50, 10, emptyList())!!.contains("меньше"))

    @Test fun needIsCapped() {
        assertEquals(27, SourceBench.aliveNeeded(3))
        assertEquals(SourceBench.NEED_CAP, SourceBench.aliveNeeded(12))
        assertEquals(SourceBench.NEED_CAP, SourceBench.aliveNeeded(50))
        assertEquals(null, SourceBench.reason(100, 12, emptyList()))
    }

    @Test fun reasonWhenNothingAlive() = assertTrue(SourceBench.reason(0, 0, emptyList()) != null)

    @Test fun shortOfIgnoresDirectAndFull() {
        val t = listOf(
            target("A", listOf("l1", "l2", "l3")),
            target("B", listOf("l1")),
            target("C", listOf(Target.DIRECT)),
            target("D", emptyList()),
        )
        assertEquals(listOf("B", "D"), SourceBench.shortOf(t))
        assertEquals(3, SourceBench.activeCount(t))
    }

    @Test fun splitKeepsOrderAndPartitions() {
        val (main, bench) = SourceBench.split(listOf("a", "b", "c"), setOf("b")) { it }
        assertEquals(listOf("a", "c"), main)
        assertEquals(listOf("b"), bench)
        val (m2, b2) = SourceBench.split(listOf("a"), emptySet()) { it }
        assertEquals(listOf("a"), m2); assertTrue(b2.isEmpty())
    }

    @Test fun highPrioritySourceIsNeverBench() {
        val ids = (1..600).map { "c$it" }.toSet()
        val m = mapOf("big" to ids, "small" to setOf("x"))
        assertEquals(setOf("big"), SourceBench.bigUrls(m, emptySet()))
        assertTrue(SourceBench.bigUrls(m, emptySet(), high = setOf("big")).isEmpty())
        assertTrue(SourceBench.benchIds(m, emptySet(), high = setOf("big")).isEmpty())
    }
}
