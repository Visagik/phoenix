package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOddsTest {
    @Test fun noDataGivesPrior() {
        assertEquals(50, ServiceOdds.percent(0, 0, 0.5))
        assertEquals(80, ServiceOdds.percent(0, 0, 0.8))
    }

    @Test fun ownDataOutweighsPrior() {
        assertTrue(ServiceOdds.percent(9, 1, 0.2) > 70)
        assertTrue(ServiceOdds.percent(0, 8, 0.9) < 25)
    }

    @Test fun priorFromAverage() {
        assertEquals(0.5, ServiceOdds.prior(0, 0), 1e-9)
        assertTrue(ServiceOdds.prior(30, 0) > 0.9)
    }
}
