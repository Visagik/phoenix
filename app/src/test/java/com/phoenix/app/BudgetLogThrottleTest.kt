package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetLogThrottleTest {
    @Test fun firstThenSilentThenAgainAfterPeriod() {
        BudgetLogThrottle.reset()
        val t = 1_000_000L
        assertTrue(BudgetLogThrottle.shouldLog(t))
        assertFalse(BudgetLogThrottle.shouldLog(t + 60_000L))
        assertFalse(BudgetLogThrottle.shouldLog(t + BudgetLogThrottle.PERIOD_MS - 1))
        assertTrue(BudgetLogThrottle.shouldLog(t + BudgetLogThrottle.PERIOD_MS))
    }
}
