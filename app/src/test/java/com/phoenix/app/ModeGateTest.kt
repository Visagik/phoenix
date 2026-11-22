package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeGateTest {
    @Test fun detectsWhenNetNotKnown() {
        assertTrue(ModeGate.needsDetect(true, "", "wifi#a1", false))
        assertTrue(ModeGate.needsDetect(true, "mobile", "wifi#a1", false))
    }

    @Test fun skipsWhenKnownOrManualOrNoNet() {
        assertFalse(ModeGate.needsDetect(true, "wifi#a1", "wifi#a1", false))
        assertFalse(ModeGate.needsDetect(false, "", "wifi#a1", false))
        assertFalse(ModeGate.needsDetect(true, "", "wifi#a1", true))
        assertFalse(ModeGate.needsDetect(true, "", "", false))
    }
}
