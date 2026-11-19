package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservePriorityTest {
    private fun cand(id: String, prio: Int = 0, alive: Boolean = true) =
        Candidate(link = "vless://u@$id.example:443#$id", identity = id, days = 1, mbps = -1.0, latencyMs = 100, alive = alive, prio = prio)

    @Test fun reserveIsHiddenWhileOthersAreAlive() {
        val l = listOf(cand("free1"), cand("paid", SourcePriority.RESERVE))
        assertEquals(listOf("free1"), PoolPolicy.applyReserve(l).map { it.identity })
    }

    @Test fun reserveIsUsedWhenNoOtherIsAlive() {
        val l = listOf(cand("free1", alive = false), cand("paid", SourcePriority.RESERVE))
        assertEquals(listOf("free1", "paid"), PoolPolicy.applyReserve(l).map { it.identity })
    }

    @Test fun noReserveChangesNothing() {
        val l = listOf(cand("a"), cand("b", SourcePriority.LOW))
        assertEquals(l, PoolPolicy.applyReserve(l))
    }

    @Test fun reserveSortsLast() {
        val ranked = Ranking.rank(listOf(cand("paid", SourcePriority.RESERVE), cand("free"), cand("low", SourcePriority.LOW)))
        assertEquals(listOf("free", "low", "paid"), ranked.map { it.identity })
    }

    @Test fun aliveReserveStaysAfterAliveMainButBeforeDeadMain() {
        val ranked = Ranking.rank(listOf(cand("deadMain", alive = false), cand("paid", SourcePriority.RESERVE), cand("aliveMain")))
        assertEquals(listOf("aliveMain", "paid", "deadMain"), ranked.map { it.identity })
    }

    @Test fun deadReserveStaysAfterDeadMain() {
        val ranked = Ranking.rank(listOf(cand("paid", SourcePriority.RESERVE, alive = false), cand("deadMain", alive = false)))
        assertEquals(listOf("deadMain", "paid"), ranked.map { it.identity })
    }

    @Test fun levelsAreClampedAndTitled() {
        assertEquals(SourcePriority.RESERVE, SourcePriority.clamp(-9))
        assertEquals(SourcePriority.HIGH, SourcePriority.clamp(9))
        assertEquals("Только если ничего нет", SourcePriority.title(SourcePriority.RESERVE))
        assertEquals("Низкий", SourcePriority.title(SourcePriority.LOW))
    }
}
