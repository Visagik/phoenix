package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactsTest {
    @Test fun fiftyToEightyUniqueOneLineFacts() {
        assertTrue(Facts.ALL.size in 50..80)
        assertEquals(Facts.ALL.size, Facts.ALL.toSet().size)
        Facts.ALL.forEach { assertTrue(it, it.isNotBlank() && !it.contains('\n') && it.length <= 130) }
    }

    @Test fun reviewedForLooksLikeVersion() {
        assertTrue(Facts.REVIEWED_FOR, Regex("\\d+\\.\\d+\\.\\d+").matches(Facts.REVIEWED_FOR))
    }

    @Test fun nextWrapsAround() {
        assertEquals(1, Facts.next(0))
        assertEquals(0, Facts.next(Facts.ALL.lastIndex))
    }

    @Test fun pickStaysInRange() {
        assertEquals(0, Facts.pick(0.0))
        assertEquals(Facts.ALL.lastIndex, Facts.pick(1.0))
        assertEquals(Facts.ALL.size / 2, Facts.pick(0.5))
    }
}
