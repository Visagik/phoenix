package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProviderTest {
    @Test fun defaultKeepsPreviousBehaviour() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), DnsProvider.DEFAULT.tun)
        assertEquals(listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "tcp://1.1.1.1"), DnsProvider.DEFAULT.secure)
    }

    @Test fun unknownIdFallsBackToDefault() {
        assertEquals(DnsProvider.DEFAULT, DnsProvider.fromId("nope"))
        assertEquals(DnsProvider.DEFAULT, DnsProvider.fromId(null))
        assertEquals(DnsProvider.QUAD9, DnsProvider.fromId("QUAD9"))
    }

    @Test fun everyProviderHasTunPlainAndSecureServers() {
        for (p in DnsProvider.entries) {
            assertTrue(p.name, p.tun.size >= 2 && p.plain.isNotEmpty() && p.secure.size >= 3)
            assertTrue(p.name, p.secure.any { it.startsWith("https://") } && p.secure.any { it.startsWith("tcp://") })
        }
    }

    @Test fun trialCountsDownAndExpires() {
        assertEquals(30, DnsTrial.remainingSec(1000L, 1000L))
        assertEquals(20, DnsTrial.remainingSec(1000L, 11_000L))
        assertEquals(0, DnsTrial.remainingSec(1000L, 31_000L))
        assertFalse(DnsTrial.expired(1000L, 30_999L))
        assertTrue(DnsTrial.expired(1000L, 31_000L))
    }

    @Test fun noTrialNeverExpiresAndBackwardsClockRollsBack() {
        assertFalse(DnsTrial.expired(0L, 999_999_999L))
        assertEquals(0, DnsTrial.remainingSec(0L, 5L))
        assertTrue(DnsTrial.expired(10_000L, 5_000L))
    }
}
