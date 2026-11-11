package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3934Test {
    private val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443?encryption=none&security=tls&sni=example.com&type=tcp#T"

    @Test fun engineChoice() {
        assertEquals(TunEngine.Kind.BUILTIN, TunEngine.choose(false, true, false).kind)
        assertEquals(TunEngine.Kind.BUILTIN, TunEngine.choose(true, false, false).kind)
        assertEquals(TunEngine.Kind.BUILTIN, TunEngine.choose(true, true, true).kind)
        assertEquals(TunEngine.Kind.HEV, TunEngine.choose(true, true, false).kind)
    }

    @Test fun hevYaml() {
        val y = TunEngine.hevYaml(1500, "10.89.64.2", "fd89:6400::2", 12345, "u'x", "p", 300, 60)
        assertTrue("  mtu: 1500" in y)
        assertTrue("  ipv4: 10.89.64.2" in y)
        assertTrue("  ipv6: 'fd89:6400::2'" in y)
        assertTrue("  port: 12345" in y)
        assertTrue("  udp: 'udp'" in y)
        assertTrue("  username: 'u''x'" in y)
        assertTrue("  tcp-read-write-timeout: 300000" in y)
        assertTrue("  udp-read-write-timeout: 60000" in y)
        assertFalse("ipv6" in TunEngine.hevYaml(1500, "10.0.0.2", null, 1, null, null))
        assertFalse("username" in TunEngine.hevYaml(1500, "10.0.0.2", null, 1, null, null))
    }

    @Test fun configUsesSocksInboundForHev() {
        val hev = XrayConfig.vpnConfig(link, XrayOptions(tunSocksPort = 23456, tunSocksAuth = SocksAuth("a", "b")))
        assertTrue("\"protocol\":\"socks\"" in hev.replace(" ", ""))
        assertTrue("23456" in hev)
        assertFalse("\"protocol\":\"tun\"" in hev.replace(" ", ""))
        val builtin = XrayConfig.vpnConfig(link, XrayOptions())
        assertTrue("\"protocol\":\"tun\"" in builtin.replace(" ", ""))
    }
}
