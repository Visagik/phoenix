package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedForTest {
    private val wl = BlockType.WHITELIST
    private val bl = BlockType.BLACKLIST

    @Test fun returnToUsualNeedsOneMeasurement() = assertEquals(1, ModeDecider.needFor(wl, Mode.BLACKLIST, Mode.WHITELIST, false))
    @Test fun leavingUsualKeepsTwo() = assertEquals(2, ModeDecider.needFor(bl, Mode.WHITELIST, Mode.WHITELIST, false))
    @Test fun noUsualKeepsTwo() = assertEquals(2, ModeDecider.needFor(wl, Mode.BLACKLIST, null, false))
    @Test fun flappingNetworkKeepsBase() = assertEquals(2, ModeDecider.needFor(wl, Mode.BLACKLIST, Mode.WHITELIST, true))
    @Test fun unclearAndOfflineChangeNothing() {
        assertEquals(2, ModeDecider.needFor(BlockType.UNKNOWN, Mode.BLACKLIST, Mode.WHITELIST, false))
        assertEquals(2, ModeDecider.needFor(BlockType.OFFLINE, Mode.BLACKLIST, Mode.WHITELIST, false))
        val r = ModeDecider.step(Vote(BlockType.UNKNOWN, 0), BlockType.UNKNOWN, Mode.BLACKLIST, 1)
        assertNull(r.second)
    }

    @Test fun detectorHoldKeepsBase() =
        assertEquals(2, ModeDecider.needVotes(wl, Mode.BLACKLIST, mapOf("n" to ModeHint.Entry(50, 0)), "n", false, true, 2))

    /** 28.11 целиком: память wl=47, bl=3, режим «чёрные», один замер «белые» → смена на белые. */
    @Test fun day28Nov() {
        val hints = mapOf("mobile:megafon" to ModeHint.Entry(47, 3))
        val need = ModeDecider.needVotes(wl, Mode.BLACKLIST, hints, "mobile:megafon", false, false, 2)
        assertEquals(1, need)
        assertEquals(Mode.WHITELIST, ModeDecider.step(Vote(BlockType.UNKNOWN, 0), wl, Mode.BLACKLIST, need).second)
    }
}
