package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolPolicyTest {
    private fun cand(id: String, cc: String, alive: Boolean = true, ms: Long = 100) =
        Candidate(link = "vless://u@$id.example:443?security=reality#$id", identity = id, days = 3, mbps = -1.0, latencyMs = ms, alive = alive, cc = cc)

    private fun reset() = PoolPolicy.configure(emptySet(), emptySet(), emptySet())

    @Test fun emptyPolicyChangesNothing() {
        reset()
        val l = listOf(cand("a", "DE"), cand("b", "RU"))
        assertEquals(l, PoolPolicy.apply(l))
        assertFalse(PoolPolicy.relaxed)
    }

    @Test fun avoidedCountryAndHiddenServerAreDropped() {
        PoolPolicy.configure(emptySet(), setOf("ru"), setOf("c"))
        val out = PoolPolicy.apply(listOf(cand("a", "DE"), cand("b", "RU"), cand("c", "FI")))
        assertEquals(listOf("a"), out.map { it.identity })
        assertFalse(PoolPolicy.relaxed)
        reset()
    }

    @Test fun restrictionsAreLiftedWhenNoLiveServerWouldRemain() {
        PoolPolicy.configure(emptySet(), setOf("RU"), emptySet())
        val l = listOf(cand("a", "RU"), cand("b", "DE", alive = false))
        assertEquals(l, PoolPolicy.apply(l))
        assertTrue(PoolPolicy.relaxed)
        reset()
    }

    @Test fun restrictionsAreLiftedWhenNothingRemains() {
        PoolPolicy.configure(emptySet(), setOf("RU"), emptySet())
        val l = listOf(cand("a", "RU"))
        assertEquals(l, PoolPolicy.apply(l))
        assertTrue(PoolPolicy.relaxed)
        reset()
    }

    @Test fun preferredCountryGoesFirstAmongLive() {
        PoolPolicy.configure(setOf("FI"), emptySet(), emptySet())
        val ranked = Ranking.rank(listOf(cand("a", "DE", ms = 20), cand("b", "FI", ms = 200)))
        assertEquals("b", ranked.first().identity)
        reset()
    }

    @Test fun preferredDeadServerDoesNotBeatLiveOne() {
        PoolPolicy.configure(setOf("FI"), emptySet(), emptySet())
        val ranked = Ranking.rank(listOf(cand("a", "DE"), cand("b", "FI", alive = false)))
        assertEquals("a", ranked.first().identity)
        reset()
    }

    @Test fun preferWinsOverAvoidForSameCountry() {
        PoolPolicy.configure(setOf("DE"), setOf("DE", "RU"), emptySet())
        assertEquals(setOf("RU"), PoolPolicy.avoid)
        reset()
    }

    @Test fun encodeDecodeRoundtrip() {
        val s = setOf("vless|a.example|443", "RU")
        assertEquals(s, PoolPolicy.decode(PoolPolicy.encode(s)))
        assertEquals(emptySet<String>(), PoolPolicy.decode(null))
    }
}
