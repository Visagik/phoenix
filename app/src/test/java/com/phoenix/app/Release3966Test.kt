package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3966Test {
    @Test fun screenOffMobileFailureIsSlower() {
        assertEquals(HealthPace.SCREEN_OFF_MOBILE_FAIL_MS, HealthPace.nextDelay(false, 1, 0, mobile = true))
        // Wi-Fi и включённый экран — как раньше
        assertEquals(HealthPace.BASE_MS, HealthPace.nextDelay(false, 1, 0, mobile = false))
        assertEquals(HealthPace.BASE_MS, HealthPace.nextDelay(true, 1, 0, mobile = true))
        // при исправной связи ничего не изменилось
        assertEquals(HealthPace.SCREEN_OFF_MS, HealthPace.nextDelay(false, 0, 0, mobile = true))
        assertTrue(HealthPace.SCREEN_OFF_MOBILE_FAIL_MS % HealthPace.GRID_MS == 0L)
    }

    @Test fun offlineLimitIsLowerOnMobile() {
        assertEquals(ServerSearch.OFFLINE_SUSPECT_MOBILE, ServerSearch.offlineLimit(true))
        assertEquals(ServerSearch.OFFLINE_SUSPECT, ServerSearch.offlineLimit(false))
        assertTrue(ServerSearch.OFFLINE_SUSPECT_MOBILE < ServerSearch.OFFLINE_SUSPECT)
    }
}
