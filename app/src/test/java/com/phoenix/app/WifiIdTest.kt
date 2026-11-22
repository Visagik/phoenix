package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIdTest {
    private fun ip(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()

    @Test fun subnetMasksOwnAddress() {
        assertEquals("192.168.1.0/24", WifiId.subnet(ip("192.168.1.57"), 24))
        assertEquals("10.4.0.0/16", WifiId.subnet(ip("10.4.77.9"), 16))
        assertEquals("192.168.1.128/25", WifiId.subnet(ip("192.168.1.200"), 25))
        assertEquals("", WifiId.subnet(ip("10.0.0.1"), 40))
    }

    @Test fun sameNetworkSameIdEvenWithOtherLeaseAndDnsOrder() {
        val a = WifiId.of("192.168.1.1", listOf("8.8.8.8", "192.168.1.1"), "lan", listOf(WifiId.subnet(ip("192.168.1.57"), 24)))
        val b = WifiId.of("192.168.1.1", listOf("192.168.1.1", "8.8.8.8"), "LAN", listOf(WifiId.subnet(ip("192.168.1.99"), 24)))
        assertEquals(a, b)
        assertEquals(6, a.length)
    }

    @Test fun differentNetworksDiffer() {
        val home = WifiId.of("192.168.1.1", listOf("192.168.1.1"), null, listOf("192.168.1.0/24"))
        val cafe = WifiId.of("172.20.10.1", listOf("172.20.10.1"), null, listOf("172.20.10.0/28"))
        assertTrue(home != cafe)
    }

    @Test fun emptyDataGivesEmptyIdAndPlainWifi() {
        assertEquals("", WifiId.of(null, emptyList(), null, emptyList()))
        assertEquals("wifi", WifiId.place("wifi", ""))
        assertEquals("wifi#ab12cd", WifiId.place("wifi", "ab12cd"))
        assertEquals("mobile:мтс", WifiId.place("mobile:мтс", "ab12cd"))
        assertTrue(WifiId.place("none", "x") == "none")
    }
}
