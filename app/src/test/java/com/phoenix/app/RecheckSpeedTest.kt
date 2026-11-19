package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RecheckSpeedTest {
    @Test fun probesByLevel() {
        assertEquals(8, RecheckSpeed.probes(RecheckSpeed.GENTLE, 16))
        assertEquals(16, RecheckSpeed.probes(RecheckSpeed.NORMAL, 16))
        assertEquals(28, RecheckSpeed.probes(RecheckSpeed.FAST, 16))
        assertEquals(8, RecheckSpeed.probes(RecheckSpeed.FAST, 5))
        assertEquals(2, RecheckSpeed.probes(RecheckSpeed.GENTLE, 3))
    }

    @Test fun clampAndLimits() {
        assertEquals(RecheckSpeed.FAST, RecheckSpeed.clamp(9))
        assertEquals(RecheckSpeed.GENTLE, RecheckSpeed.clamp(-3))
        assertEquals(12, RecheckSpeed.tcpCap(RecheckSpeed.GENTLE))
        assertEquals(48, RecheckSpeed.tcpCap(RecheckSpeed.FAST))
        assertEquals(1_800, RecheckSpeed.reachMs(RecheckSpeed.FAST))
        assertEquals(2_500, RecheckSpeed.reachMs(RecheckSpeed.NORMAL))
    }
}
