package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CarryOverTest {
    private val today = LocalDate.of(2026, 11, 14)
    private fun link(i: Int) = "trojan://pw@203.0.113.$i:443#Server $i"
    private val key = { l: String -> CheckStore.keyOf(l) }

    @Test fun keepsWorkingOldConfigsAndSkipsDuplicatesOfNewOnes() {
        val prev = (1..5).map(::link)
        val newKeys = setOf(key(link(1)), key(link(2)))
        val out = CarryOver.select(prev, newKeys, emptyMap(), today, 3, key) { true }
        assertEquals(listOf(link(3), link(4), link(5)), out)
    }

    @Test fun deadConfigsAreNotCarried() {
        val dead = key(link(2))
        val out = CarryOver.select((1..3).map(::link), emptySet(), emptyMap(), today, 3, key) { it != dead }
        assertEquals(listOf(link(1), link(3)), out)
    }

    @Test fun expiresAfterConfiguredDays() {
        val seen = mapOf(key(link(1)) to today.minusDays(3), key(link(2)) to today.minusDays(4))
        val out = CarryOver.select(listOf(link(1), link(2)), emptySet(), seen, today, 3, key) { true }
        assertEquals(listOf(link(1)), out)
    }

    @Test fun zeroDaysDisablesCarry() {
        assertTrue(CarryOver.select(listOf(link(1)), emptySet(), emptyMap(), today, 0, key) { true }.isEmpty())
    }

    @Test fun touchRefreshesFreshOnlyAndPrunesOld() {
        val seen = mapOf("old" to today.minusDays(40), "kept" to today.minusDays(2))
        val out = CarryOver.touch(seen, setOf("new"), setOf("carried"), today)
        assertEquals(setOf("kept", "new", "carried"), out.keys)
        assertEquals(today.minusDays(2), out["kept"])
        assertEquals(today, out["new"])
    }

    @Test fun roundTrip() {
        val m = mapOf("a" to today, "b" to today.minusDays(1))
        assertEquals(m, CarryOver.decode(CarryOver.encode(m)))
    }
}
