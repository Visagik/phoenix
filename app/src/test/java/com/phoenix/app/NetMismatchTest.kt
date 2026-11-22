package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetMismatchTest {
    @Test fun ignoresBriefMismatch() = assertTrue(NetMismatch.ignore(true, true, 1000, 1000 + 10_000))

    @Test fun acceptsLongMismatchAsMobileResult() = assertFalse(NetMismatch.ignore(true, true, 1000, 1000 + NetMismatch.GRACE_MS))

    @Test fun noMismatchWhenMeasuredNetMatches() {
        assertFalse(NetMismatch.ignore(false, true, 1000, 2000))
        assertFalse(NetMismatch.ignore(true, false, 1000, 2000))
        assertFalse(NetMismatch.ignore(true, true, 0, 2000))
    }
}
