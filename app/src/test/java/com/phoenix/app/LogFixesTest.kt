package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFixesTest {
    private fun vless(host: String, query: String) = "vless://11111111-1111-1111-1111-111111111111@$host:443?$query#t"

    @Test fun vlessWithoutTlsOnPublicIpIsUnsupported() {
        assertFalse(XrayConfig.supports(vless("8.8.4.4", "type=tcp&security=none")))
        assertFalse(XrayConfig.supports(vless("8.8.4.4", "type=ws")))
    }

    @Test fun vlessWithoutTlsOnDomainIsKept() {
        assertTrue(XrayConfig.supports(vless("example.com", "type=tcp&security=none")))
        assertTrue(XrayConfig.supports(vless("8.8.4.4", "type=tcp&security=tls&sni=example.com")))
    }

    // раньше частные адреса не фильтровались вовсе ни для одного протокола (находка Фейбла №1.3) — публичный список
    // мог указать на устройство в домашней сети того, кто его публикует, и фоновая перепроверка сама слала туда запрос
    @Test fun privateOrLoopbackServerAddressIsRejectedForEveryProtocol() {
        assertFalse(XrayConfig.supports(vless("192.168.1.5", "type=tcp&security=none")))
        assertFalse(XrayConfig.supports("trojan://pw@10.0.0.1:443#t"))
        assertFalse(XrayConfig.supports("trojan://pw@127.0.0.1:443#t"))
        val ssUser = java.util.Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray())
        assertFalse(XrayConfig.supports("ss://$ssUser@172.16.0.1:8388#t"))
        assertFalse(XrayConfig.supports("hysteria2://pw@169.254.1.1:443?sni=example.com#t"))
        assertFalse(XrayConfig.supports("trojan://pw@[fe80::1]:443#t"))
        assertTrue(XrayConfig.supports("trojan://pw@1.2.3.4:443#t"))
    }

    @Test fun allowInsecureWithoutDomainIsUnsupportedButWithSniOk() {
        assertFalse(XrayConfig.supports(vless("8.8.4.4", "security=tls&allowInsecure=1")))
        assertTrue(XrayConfig.supports(vless("8.8.4.4", "security=tls&allowInsecure=1&sni=example.com")))
        val cfg = XrayConfig.probeConfig(vless("8.8.4.4", "security=tls&allowInsecure=1&sni=example.com"))
        assertFalse(cfg.contains("allowInsecure"))
    }

    @Test fun pinnedCertIsPassedThrough() {
        val cfg = XrayConfig.probeConfig(vless("8.8.4.4", "security=tls&allowInsecure=1&pcs=abcd&vcn=example.com"))
        assertTrue(cfg.contains("pinnedPeerCertSha256"))
        assertTrue(cfg.contains("verifyPeerCertByName"))
    }

    @Test fun baselineFallsBackWhenFirstUrlGives403() {
        val tried = ArrayList<String>()
        var err = ""
        val ms = Baseline.measure({ u ->
            tried += u
            if (u.contains("cloudflare")) 120L else { err = "invalid status: 403 Forbidden"; -1L }
        }, { err })
        assertEquals(120L, ms)
        assertEquals(2, tried.size)
    }

    @Test fun baselineTriesCloudflareOnceOnPlainFailure() {
        val urls = ArrayList<String>()
        val ms = Baseline.measure({ urls += it; -1L }, { "i/o timeout" })
        assertEquals(-1L, ms)
        assertEquals(listOf(Baseline.PRIMARY, Baseline.ALTERNATIVES.first()), urls)
        // gstatic закрыт, а Cloudflare по http отвечает — сервер жив
        assertEquals(60L, Baseline.measure({ if (it == Baseline.PRIMARY) -1L else 60L }, { "i/o timeout" }))
    }

    @Test fun baselineRetriesOnClosedPipe() {
        var n = 0
        val ms = Baseline.measure({ n++; if (n == 1) -1L else 90L }, { "io: read/write on closed pipe" }, pause = {})
        assertEquals(90L, ms)
        assertEquals(2, n)
    }

    @Test fun publicIpDetection() {
        assertTrue(XrayConfig.isPublicIp("1.2.3.4"))
        assertFalse(XrayConfig.isPublicIp("10.0.0.1"))
        assertFalse(XrayConfig.isPublicIp("172.20.1.1"))
        assertTrue(XrayConfig.isPublicIp("172.40.1.1"))
        assertFalse(XrayConfig.isPublicIp("example.com"))
    }
}
