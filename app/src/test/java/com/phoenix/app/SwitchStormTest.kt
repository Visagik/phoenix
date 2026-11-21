package com.phoenix.app

import org.junit.Test
import org.junit.Assert.assertEquals

class SwitchStormTest {
    @Test
    fun fewSwitchesAreFree() {
        assertEquals(0L, SwitchStorm.holdMs(listOf(1_000L, 60_000L, 120_000L), 130_000L))
    }

    @Test
    fun burstGrowsHoldUpToLimit() {
        val t = (1..5).map { it * 20_000L }
        assertEquals(40_000L, SwitchStorm.holdMs(t, 110_000L))
        val many = (1..20).map { it * 1_000L }
        assertEquals(SwitchStorm.HOLD_MAX_MS, SwitchStorm.holdMs(many, 25_000L))
    }

    @Test
    fun oldSwitchesDoNotCount() {
        val t = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L)
        assertEquals(0L, SwitchStorm.holdMs(t, SwitchStorm.WINDOW_MS + 100_000L))
    }
}
