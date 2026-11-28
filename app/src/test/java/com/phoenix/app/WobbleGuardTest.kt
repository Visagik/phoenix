package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WobbleGuardTest {
    @Test fun whitelistOnShakyWifiIsIgnored() = assertTrue(WobbleGuard.ignore(BlockType.WHITELIST, Mode.BLACKLIST, measuredWifi = true, shaky = true))

    @Test fun stableWifiCountsAsUsual() = assertFalse(WobbleGuard.ignore(BlockType.WHITELIST, Mode.BLACKLIST, measuredWifi = true, shaky = false))

    @Test fun mobileWhitelistIsNeverDelayed() = assertFalse(WobbleGuard.ignore(BlockType.WHITELIST, Mode.BLACKLIST, measuredWifi = false, shaky = true))

    @Test fun returnToBlacklistIsNeverDelayed() {
        assertFalse(WobbleGuard.ignore(BlockType.BLACKLIST, Mode.WHITELIST, measuredWifi = true, shaky = true))
        assertFalse(WobbleGuard.ignore(BlockType.WHITELIST, Mode.WHITELIST, measuredWifi = true, shaky = true))
    }

    @Test fun shakyDoesNotCountMeasurementWobble() {
        NetState.clear()
        NetState.wobble(1_000) // дрожь ставит само определение: «шаткостью» она не считается
        assertFalse(NetState.isShaky(2_000))
        NetState.shake(1_000)
        assertTrue(NetState.isShaky(2_000))
        NetState.clear()
    }
}
