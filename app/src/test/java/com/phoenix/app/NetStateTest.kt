package com.phoenix.app

import com.phoenix.app.NetState.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetStateTest {
    @Test fun stableByDefault() {
        NetState.clear()
        assertEquals(Link.STABLE, NetState.link(1_000_000))
    }

    @Test fun shakeSettlesForTheWindow() {
        NetState.clear()
        NetState.shake(1_000)
        assertEquals(Link.SETTLING, NetState.link(1_000 + NetState.SETTLE_MS - 1))
        assertEquals(Link.STABLE, NetState.link(1_000 + NetState.SETTLE_MS))
        assertTrue(NetState.recentShake(1_000 + NetState.RECENT_MS - 1))
        assertFalse(NetState.recentShake(1_000 + NetState.RECENT_MS))
    }

    @Test fun wobbleCountsAsSettling() {
        NetState.clear()
        NetState.wobble(5_000)
        assertEquals(Link.SETTLING, NetState.link(10_000))
        assertEquals(Link.STABLE, NetState.link(5_000 + NetState.SETTLE_MS))
    }

    @Test fun flapHoldsAndSettlingWinsOverIt() {
        NetState.clear()
        NetState.flap(1_000)
        assertEquals(Link.FLAPPING, NetState.link(1_001))
        assertTrue(NetFlap.busy(1_001))
        NetState.shake(1_000)
        assertEquals(Link.SETTLING, NetState.link(1_001)) // как раньше: сначала «ждём», потом «прыгает»
        assertTrue(NetState.isFlapping(1_001))
        assertEquals(Link.STABLE, NetState.link(1_000 + NetState.FLAP_HOLD_MS))
    }
}
