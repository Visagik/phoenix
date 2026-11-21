package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionVoteTest {
    private fun seq(vararg v: Boolean?): () -> Boolean? { var i = 0; return { v[i++] } }

    @Test
    fun twoAgreeingReadsDecide() {
        assertEquals(true, RegionVote.decide(3, seq(true, true)))
        assertEquals(false, RegionVote.decide(3, seq(false, false)))
    }

    @Test
    fun disagreementIsSettledByThird() {
        assertEquals(false, RegionVote.decide(3, seq(true, false, false)))
        assertEquals(true, RegionVote.decide(3, seq(false, true, true)))
    }

    @Test
    fun unknownReadsDoNotVote() {
        assertEquals(false, RegionVote.decide(3, seq(null, false, false)))
        assertNull(RegionVote.decide(3, seq(null, null, null)))
        assertNull(RegionVote.decide(3, seq(true, false, null)))
    }

    @Test
    fun singleReadForDeadConfigs() {
        assertEquals(true, RegionVote.decide(1, seq(true)))
        assertNull(RegionVote.decide(1, seq(null)))
    }
}
