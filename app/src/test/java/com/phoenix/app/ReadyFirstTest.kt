package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyFirstTest {
    private fun cand(id: String) = Candidate("vless://x@$id.example:443#$id", id, 3, -1.0, 0, 0)

    @Test
    fun readyServersGoFirstInQueueOrder() {
        val q = listOf(cand("a"), cand("b"), cand("c"), cand("d"))
        val ready = ReadyFirst.pick(q, setOf("c", "a"))
        assertEquals(listOf("a", "c"), ready.map { it.identity })
    }

    @Test
    fun noStageWhenNothingToSeparate() {
        val q = listOf(cand("a"), cand("b"))
        assertTrue(ReadyFirst.pick(q, null).isEmpty())
        assertTrue(ReadyFirst.pick(q, emptySet()).isEmpty())
        assertTrue(ReadyFirst.pick(q, setOf("x")).isEmpty())
        assertTrue(ReadyFirst.pick(q, setOf("a", "b")).isEmpty())
    }

    @Test
    fun firstStageIsLimitedToDozens() {
        val q = (1..200).map { cand("s$it") }
        val fit = (1..150).map { "s$it" }.toSet()
        val ready = ReadyFirst.pick(q, fit)
        assertEquals(ReadyFirst.STAGE_LIMIT, ready.size)
        assertEquals("s1", ready.first().identity)
    }
}
