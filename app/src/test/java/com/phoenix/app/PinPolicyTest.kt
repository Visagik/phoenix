package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPolicyTest {
    private val h = 3_600_000L

    @Test fun zeroMeansUntilDisconnect() {
        assertFalse(PinPolicy.expired(1L, 1L + 100 * h, 0))
    }

    @Test fun expiresAfterConfiguredHours() {
        assertFalse(PinPolicy.expired(1000L, 1000L + 59 * 60_000L, 1))
        assertTrue(PinPolicy.expired(1000L, 1000L + h, 1))
        assertFalse(PinPolicy.expired(1000L, 1000L + 5 * h, 6))
        assertTrue(PinPolicy.expired(1000L, 1000L + 24 * h, 24))
    }

    @Test fun unsetTimeNeverExpires() {
        assertFalse(PinPolicy.expired(0L, 100 * h, 1))
    }

    @Test fun clampAndLabels() {
        assertEquals(0, PinPolicy.clamp(5))
        assertEquals(6, PinPolicy.clamp(6))
        assertEquals("Сутки", PinPolicy.label(24))
        assertEquals("Пока не отключу", PinPolicy.label(0))
    }
}
