package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundBudgetTest {
    private val mb = 1024L * 1024L
    private val day = 86_400_000L

    @Test fun zeroMeansNoLimit() {
        assertFalse(BackgroundBudget.exhausted(10_000 * mb, 0))
    }

    @Test fun limitReachedAtExactly() {
        assertFalse(BackgroundBudget.exhausted(99 * mb, 100))
        assertTrue(BackgroundBudget.exhausted(100 * mb, 100))
    }

    @Test fun unknownChoiceFallsBackToDefault() {
        assertEquals(100, BackgroundBudget.clampMb(77))
        assertEquals(0, BackgroundBudget.clampMb(0))
        assertEquals(300, BackgroundBudget.clampMb(300))
    }

    @Test fun wifiOnlyAppliesWhileCollectionIsFresh() {
        assertTrue(WifiOnlyPolicy.effective(true, manual = false, lastSuccessAt = 10 * day, now = 11 * day))
        assertFalse(WifiOnlyPolicy.relaxed(true, manual = false, lastSuccessAt = 10 * day, now = 11 * day))
    }

    @Test fun wifiOnlyRelaxesAfterThreeDays() {
        assertFalse(WifiOnlyPolicy.effective(true, manual = false, lastSuccessAt = 10 * day, now = 13 * day + 1))
        assertTrue(WifiOnlyPolicy.relaxed(true, manual = false, lastSuccessAt = 10 * day, now = 13 * day + 1))
    }

    @Test fun manualAndNeverCollectedIgnoreWifiOnly() {
        assertFalse(WifiOnlyPolicy.effective(true, manual = true, lastSuccessAt = 10 * day, now = 10 * day))
        assertFalse(WifiOnlyPolicy.effective(true, manual = false, lastSuccessAt = 0, now = 10 * day))
        assertFalse(WifiOnlyPolicy.effective(false, manual = false, lastSuccessAt = 10 * day, now = 10 * day))
    }
}
