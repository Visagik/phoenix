package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundHealthTest {
    @Test fun allGood() = assertEquals(HealthLevel.OK, BackgroundHealth.level(true, true, true))
    @Test fun missingPermissionWarns() {
        assertEquals(HealthLevel.WARN, BackgroundHealth.level(false, true, true))
        assertEquals(HealthLevel.WARN, BackgroundHealth.level(true, false, true))
    }
    @Test fun noAlarmIsBadEvenWithPermissions() = assertEquals(HealthLevel.BAD, BackgroundHealth.level(true, true, false))
}
