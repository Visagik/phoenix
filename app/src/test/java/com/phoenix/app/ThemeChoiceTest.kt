package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeChoiceTest {
    @Test fun followsSystemByDefault() {
        assertTrue(ThemeChoice.dark(ThemeChoice.SYSTEM, true))
        assertFalse(ThemeChoice.dark(ThemeChoice.SYSTEM, false))
    }

    @Test fun explicitChoiceWins() {
        assertFalse(ThemeChoice.dark(ThemeChoice.LIGHT, true))
        assertTrue(ThemeChoice.dark(ThemeChoice.DARK, false))
    }

    @Test fun garbageFallsBackToSystem() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.clamp(7))
        assertTrue(ThemeChoice.dark(-3, true))
    }

    @Test fun logSizes() {
        assertEquals(1L * 1024 * 1024, LogSize.maxBytes(LogSize.SHORT))
        assertEquals(5L * 1024 * 1024, LogSize.maxBytes(LogSize.NORMAL))
        assertEquals(20L * 1024 * 1024, LogSize.maxBytes(LogSize.DETAILED))
        assertEquals(5L * 1024 * 1024, LogSize.maxBytes(99))
        assertTrue(LogSize.keepBytes(LogSize.SHORT) < LogSize.maxBytes(LogSize.SHORT))
    }
}
