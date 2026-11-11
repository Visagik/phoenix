package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3941Test {
    private fun warmed(): GatewayLoad = GatewayLoad().also { g -> repeat(GatewayLoad.CALIBRATION) { g.feed(5) } }

    @Test fun lossesInWindowMakeGatewayBusyEvenWithFastAnswers() {
        val g = warmed()
        var busy = false
        // два потеряны из восьми (четверть) — это уже перегрузка, хотя отвечавшие замеры быстрые
        listOf(5L, 6L, 5L, -1L, 5L, 6L, -1L, 5L, 5L).forEach { if (g.feed(it) == GatewayLoad.Verdict.BUSY) busy = true }
        assertTrue(busy)
    }

    @Test fun jitterMakesGatewayBusy() {
        val g = warmed()
        var busy = false
        listOf(5L, 6L, 5L, 120L, 6L, 140L, 5L, 6L, 300L).forEach { if (g.feed(it) == GatewayLoad.Verdict.BUSY) busy = true }
        assertTrue(busy)
    }

    @Test fun calmGatewayStaysOk() {
        val g = warmed()
        repeat(20) { assertEquals(GatewayLoad.Verdict.OK, g.feed(5L + it % 3)) }
    }

    @Test fun vpnRetryIsLimited() {
        assertTrue(VpnRetryBudget.allow(false, 0, false))
        assertFalse(VpnRetryBudget.allow(true, 0, false))
        assertFalse(VpnRetryBudget.allow(false, 0, true))
        assertFalse(VpnRetryBudget.allow(false, VpnRetryBudget.MAX_PER_RUN, false))
    }

    @Test fun subscriptionTokenIsMaskedInLog() {
        val line = "FETCH: https://your-durev.com/sub/AbCdEfGhIjKlMnOpQrStUv123456/white/ код 200"
        val out = LogMask.apply(line)
        assertFalse(out.contains("AbCdEfGhIjKlMnOpQrStUv123456"))
        assertTrue(out.contains("your-durev.com/sub/AbCd…/white/"))
        assertTrue(out.endsWith("код 200"))
    }

    @Test fun secretQueryValuesAreMaskedButOrdinaryOnesKept() {
        val out = LogMask.url("https://x.example/api?token=supersecret123&lang=ru")
        assertTrue(out.contains("token=su…"))
        assertTrue(out.contains("lang=ru"))
        assertEquals("https://github.com/user/repo/raw/main/list.txt", LogMask.url("https://github.com/user/repo/raw/main/list.txt"))
    }

    @Test fun linesWithoutUrlsAreUntouched() {
        assertEquals("HEALTH: нет связи", LogMask.apply("HEALTH: нет связи"))
    }
}
