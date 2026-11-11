package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3938Test {
    @Test fun vpnRetryCodes() {
        for (c in listOf(0, 200, 401, 403, 408, 429, 451, 500, 503)) assertTrue("$c", FetchPolicy.tryVpn(c))
        for (c in listOf(404, 410, 301)) assertFalse("$c", FetchPolicy.tryVpn(c))
    }

    @Test fun uncertainFailures() {
        assertTrue(FetchPolicy.uncertain(0))
        assertTrue(FetchPolicy.uncertain(403))
        assertFalse(FetchPolicy.uncertain(404))
        assertFalse(FetchPolicy.uncertain(500))
    }

    @Test fun userAgentsRotate() {
        assertEquals("Phoenix/1.0", FetchPolicy.userAgent(0))
        assertTrue(FetchPolicy.userAgent(1).startsWith("v2rayNG"))
        assertTrue(FetchPolicy.userAgent(2).startsWith("Mozilla"))
        assertEquals(FetchPolicy.userAgent(2), FetchPolicy.userAgent(9))
        assertEquals(FetchPolicy.userAgent(0), FetchPolicy.userAgent(-3))
    }

    @Test fun subscriptionDetection() {
        assertTrue(FetchPolicy.looksLikeSubscription("vless://a@h:1#x\nvmess://zzz"))
        val b64 = java.util.Base64.getEncoder().encodeToString("trojan://p@h:443#T\n".toByteArray())
        assertTrue(FetchPolicy.looksLikeSubscription(b64))
        assertFalse(FetchPolicy.looksLikeSubscription("<html><body>Just a moment... https://example.com</body></html>"))
        assertFalse(FetchPolicy.looksLikeSubscription(""))
        assertTrue(FetchPolicy.isRedirect(302))
        assertFalse(FetchPolicy.isRedirect(200))
    }

    @Test fun describe() {
        assertEquals("напрямую: код 403; через VPN: код 200", FetchPolicy.describe(403, 200, true))
        assertEquals("напрямую: нет ответа; VPN выключен — через него не пробовал", FetchPolicy.describe(0, null, false))
        assertEquals("напрямую: код 404", FetchPolicy.describe(404, null, false))
    }
}
