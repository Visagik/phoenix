package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSourcesLowTrustTest {
    @Test fun genericListsAreLowTrust() {
        assertTrue(DefaultSources.isLowTrust("https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt"))
        assertTrue(DefaultSources.isLowTrust("https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/protocols/vless.txt"))
    }
    @Test fun removedListsAreGone() {
        val all = DefaultSources.forMode(Mode.BLACKLIST) + DefaultSources.forMode(Mode.WHITELIST)
        assertFalse(all.any { it.contains("itsyebekhe/PSG") || it.contains("barry-far") || it.contains("sakha1370/OpenRay") })
    }

    @Test fun removedHugeListIsGone() {
        assertFalse(DefaultSources.forMode(Mode.BLACKLIST).any { it.contains("kasesm") })
        assertFalse(DefaultSources.forMode(Mode.WHITELIST).any { it.contains("kasesm") })
    }

    @Test fun russianListsAreNot() {
        assertFalse(DefaultSources.isLowTrust("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt"))
        assertFalse(DefaultSources.isLowTrust("https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt"))
    }
}
