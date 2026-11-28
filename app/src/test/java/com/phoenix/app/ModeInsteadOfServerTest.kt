package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeInsteadOfServerTest {
    private val now = 1_000_000L
    private fun f(auto: Boolean = true, held: Boolean = false, cur: Mode = Mode.BLACKLIST, t: BlockType? = BlockType.WHITELIST, at: Long = now - 15_000) =
        FailoverGate.modeInsteadOfServer(auto, held, cur, t, at, now)

    @Test fun freshOtherModeMeansChangeMode() = assertEquals(Mode.WHITELIST, f())
    @Test fun threeMinutesOldIsIgnored() = assertNull(f(at = now - 180_000))
    @Test fun sameModeNull() = assertNull(f(cur = Mode.WHITELIST))
    @Test fun unclearAndOfflineNull() {
        assertNull(f(t = BlockType.UNKNOWN))
        assertNull(f(t = BlockType.OFFLINE))
        assertNull(f(t = null))
    }
    @Test fun manualHoldOrAutoOffNull() {
        assertNull(f(held = true))
        assertNull(f(auto = false))
    }
}
