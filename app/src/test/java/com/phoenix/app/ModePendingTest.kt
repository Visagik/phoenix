package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePendingTest {
    @Test fun pendingWhenDetectionDisagreesRecently() {
        assertTrue(FailoverGate.modePending(Mode.BLACKLIST, BlockType.WHITELIST, 1_000, 30_000))
    }

    @Test fun notPendingWhenSameOldOrUnclear() {
        assertEquals(false, FailoverGate.modePending(Mode.WHITELIST, BlockType.WHITELIST, 1_000, 2_000))
        assertEquals(false, FailoverGate.modePending(Mode.BLACKLIST, BlockType.WHITELIST, 1_000, 500_000))
        assertEquals(false, FailoverGate.modePending(Mode.BLACKLIST, BlockType.OFFLINE, 1_000, 2_000))
        assertEquals(false, FailoverGate.modePending(Mode.BLACKLIST, null, 0, 2_000))
    }
}
