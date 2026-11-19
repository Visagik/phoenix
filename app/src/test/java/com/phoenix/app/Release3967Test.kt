package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3967Test {
    @Test fun directTargetPausedForADay() {
        val m = mapOf("T1" to 1000L, MissBackoff.directKey("T1") to 1000L)
        // обычная пауза 6 ч закончилась, а «мимо VPN» ещё держится
        val after7h = 1000L + MissBackoff.TTL_MS + 60_000L
        assertFalse(MissBackoff.skip(m, "T1", after7h))
        assertTrue(MissBackoff.paused(m, "T1", after7h))
        assertFalse(MissBackoff.paused(m, "T1", 1000L + MissBackoff.DIRECT_TTL_MS + 1))
    }

    @Test fun plainMissKeepsSixHours() {
        val m = mapOf("T2" to 1000L)
        assertTrue(MissBackoff.paused(m, "T2", 1000L + 60_000L))
        assertFalse(MissBackoff.paused(m, "T2", 1000L + MissBackoff.TTL_MS + 1))
        assertFalse(MissBackoff.paused(m, "T3", 5000L))
    }

    @Test fun directKeySurvivesRenderParse() {
        val m = MissBackoff.parse(MissBackoff.render(mapOf(MissBackoff.directKey("T1") to 42L)))
        assertTrue(m.containsKey(MissBackoff.directKey("T1")))
    }
}
