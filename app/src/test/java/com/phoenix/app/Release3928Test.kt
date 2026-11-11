package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class Release3928Test {
    private fun hy(link: String): Map<String, Any?> = XrayConfig.outbound(link)

    @Test fun hysteria2LinkParses() {
        val p = LinkParser.parse("hysteria2://pass%40word@example.com:8443/?sni=cdn.example.com#DE fast")!!
        assertEquals("hysteria2", p.protocol)
        assertEquals("example.com", p.host)
        assertEquals(8443, p.port)
        assertEquals(62, p.priority)
        assertEquals("DE fast", p.title)
    }

    @Test fun hy2AliasIsNormalized() {
        assertEquals("hysteria2", LinkParser.parse("hy2://a@h.example.com:443#x")!!.protocol)
    }

    @Test fun tuicStaysDropped() {
        assertNull(LinkParser.parse("tuic://a@h.example.com:443#x"))
    }

    @Test fun hysteria2Outbound() {
        val o = hy("hysteria2://pw@example.com:8443/?sni=cdn.example.com#x")
        assertEquals("hysteria", o["protocol"])
        val settings = o["settings"] as Map<*, *>
        assertEquals(2, settings["version"])
        assertEquals("example.com", settings["address"])
        assertEquals(8443, settings["port"])
        val ss = o["streamSettings"] as Map<*, *>
        assertEquals("hysteria", ss["network"])
        assertEquals("tls", ss["security"])
        assertEquals("pw", (ss["hysteriaSettings"] as Map<*, *>)["auth"])
        assertEquals("cdn.example.com", (ss["tlsSettings"] as Map<*, *>)["serverName"])
    }

    @Test fun hysteria2UnsafeOrUnknownIsRejected() {
        fun bad(l: String) = try { XrayConfig.vpnConfig(l); false } catch (_: UnsupportedLinkException) { true }
        assertTrue(bad("hysteria2://pw@example.com:443/?obfs=salamander&obfs-password=x#a"))
        assertTrue(bad("hysteria2://pw@example.com:443/?insecure=1#a"))
        assertTrue(bad("hysteria2://pw@1.2.3.4:443/#a"))
        assertTrue(bad("hysteria2://@example.com:443/#a"))
        assertFalse(bad("hysteria2://pw@example.com:443/#a"))
        assertFalse(bad("hysteria2://pw@1.2.3.4:443/?insecure=1&pinSHA256=AA:BB:CC#a"))
    }

    @Test fun pinIsNormalized() {
        val o = hy("hysteria2://pw@1.2.3.4:443/?insecure=1&pinSHA256=AA:BB:CC#a")
        val tls = (o["streamSettings"] as Map<*, *>)["tlsSettings"] as Map<*, *>
        assertEquals("aabbcc", tls["pinnedPeerCertSha256"])
    }

    @Test fun oemDetection() {
        assertEquals(OemGuide.Vendor.XIAOMI, OemGuide.vendor("Xiaomi", "Redmi"))
        assertEquals(OemGuide.Vendor.XIAOMI, OemGuide.vendor("Xiaomi", "POCO"))
        assertEquals(OemGuide.Vendor.VIVO, OemGuide.vendor("vivo", "vivo"))
        assertEquals(OemGuide.Vendor.OPPO, OemGuide.vendor("OnePlus", "OnePlus"))
        assertEquals(OemGuide.Vendor.HUAWEI, OemGuide.vendor("HONOR", ""))
        assertEquals(OemGuide.Vendor.SAMSUNG, OemGuide.vendor("samsung", "samsung"))
        assertEquals(OemGuide.Vendor.OTHER, OemGuide.vendor("Google", "google"))
        assertTrue(OemGuide.autostart(OemGuide.Vendor.XIAOMI).isNotEmpty())
        assertTrue(OemGuide.autostart(OemGuide.Vendor.SAMSUNG).isEmpty())
        assertNotNull(OemGuide.hint(OemGuide.Vendor.OTHER))
    }

    @Test fun mobileCap() {
        assertEquals(16, ParallelGovernor.capFor(48, true))
        assertEquals(8, ParallelGovernor.capFor(8, true))
        assertEquals(48, ParallelGovernor.capFor(48, false))
    }

    @Test fun hotPaceIsSlowerOnlyWhenCalm() {
        assertEquals(HealthPace.HOT_MS, HealthPace.nextDelay(true, 0, 0, hot = true))
        assertEquals(HealthPace.BASE_MS, HealthPace.nextDelay(true, 1, 0, hot = true))
    }
}
