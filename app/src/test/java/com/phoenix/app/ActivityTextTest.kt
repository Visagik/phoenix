package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityTextTest {
    @Test fun etaNeedsData() {
        assertEquals(-1, ActivityText.etaSeconds(10, 100, 60_000))
        assertEquals(-1, ActivityText.etaSeconds(50, 100, 3_000))
        assertEquals(-1, ActivityText.etaSeconds(100, 100, 60_000))
    }
    @Test fun etaLinear() {
        assertEquals(60, ActivityText.etaSeconds(50, 100, 60_000))
    }
    @Test fun percentAndSpan() {
        assertEquals(95, ActivityText.percent(1625, 1709))
        assertEquals(0, ActivityText.percent(5, 0))
    }
}

class ActivityStyleTest {
    @Test fun clampKeepsKnownAndDefaultsOthers() {
        assertEquals(ActivityStyle.LOG, ActivityStyle.clamp(3))
        assertEquals(ActivityStyle.OFF, ActivityStyle.clamp(0))
        assertEquals(ActivityStyle.DEFAULT, ActivityStyle.clamp(9))
        assertEquals(ActivityStyle.DEFAULT, ActivityStyle.clamp(-1))
    }
}
