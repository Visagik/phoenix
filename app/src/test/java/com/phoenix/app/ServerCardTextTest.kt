package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCardTextTest {
    private fun e(ping: Long? = 64, dead: Boolean = false, checked: String = "2026-11-19", ok: Int = 6, partial: Int = 1, blocked: Int = 0) =
        ServerEntry("vless://x", "Latvia 3", "vless", true, "LV", 3, ping, null, ok, partial, blocked, 0, checked, dead)

    @Test fun latencyToneByThresholds() {
        assertEquals(0, ServerTable.latencyTone(e(ping = 150)))
        assertEquals(1, ServerTable.latencyTone(e(ping = 151)))
        assertEquals(1, ServerTable.latencyTone(e(ping = 400)))
        assertEquals(2, ServerTable.latencyTone(e(ping = 401)))
        assertEquals(2, ServerTable.latencyTone(e(ping = null)))
        assertEquals(2, ServerTable.latencyTone(e(dead = true)))
    }

    @Test fun latencyTextForUnknownIsDash() {
        assertEquals("—", ServerTable.latencyText(e(ping = null)))
        assertEquals("—", ServerTable.latencyText(e(ping = 0)))
    }

    @Test fun subtitleHasProtocolAndServicesButNoNoise() {
        val s = ServerTable.subtitle(e())
        assertTrue(s.startsWith("VLESS Reality"))
        assertTrue(s.contains("6✓"))
        assertFalse(s.contains("??"))
        assertFalse(s.contains("дн."))
    }

    @Test fun subtitleOfDeadOrUncheckedHasNoServices() {
        assertEquals("VLESS Reality", ServerTable.subtitle(e(dead = true)))
        assertEquals("VLESS Reality", ServerTable.subtitle(e(checked = "")))
    }

    @Test fun shortSummaryShowsFilteredCountOnlyWhenFiltered() {
        val list = listOf(e(), e(dead = true), e(checked = ""))
        assertEquals("3 · живых 1", ServerTable.shortSummary(list, 3))
        assertEquals("3 · живых 1 · показано 2", ServerTable.shortSummary(list, 2))
    }
}
