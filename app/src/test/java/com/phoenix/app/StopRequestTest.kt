package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopRequestTest {
    @Test
    fun stopAffectsWorkStartedBeforeItAndTheChainAfterIt() {
        StopRequest.at.value = 0L
        assertFalse(StopRequest.requestedSince(1_000))
        StopRequest.request(5_000)
        assertTrue(StopRequest.requestedSince(4_000))
        assertTrue(StopRequest.requestedSince(6_000)) // следующий этап цепочки (сбор → перепроверка → цели)
        assertTrue(StopRequest.requestedSince(5_000 + StopRequest.CHAIN_MS))
        assertFalse(StopRequest.requestedSince(5_000 + StopRequest.CHAIN_MS + 1)) // новая работа позже окна не отменяется
        StopRequest.at.value = 0L
    }

    @Test
    fun manualStartClearsPreviousRequest() {
        StopRequest.request(5_000)
        StopRequest.clear()
        assertFalse(StopRequest.requestedSince(6_000))
        assertFalse(StopRequest.pending(6_000))
    }

    @Test
    fun detailRateAndClock() {
        val d = RecheckDetail(100, 60, 10, 30, 15, 5, 0, 120_000, 1, 48, 12, 0, false)
        assertEquals(30, d.perMinute())
        assertEquals(15, d.portOpenNoInternet())
        assertEquals("2:00", ActivityText.clock(120_000))
        assertEquals("1:01:05", ActivityText.clock(3_665_000))
        assertEquals(-1, RecheckDetail(100, 3, 1, 1, 1, 0, 0, 3_000, 1, 1, 1, 0, false).perMinute())
    }
}
