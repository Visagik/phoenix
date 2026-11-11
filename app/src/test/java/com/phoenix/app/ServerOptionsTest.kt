package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOptionsTest {
    private fun link(name: String, host: String) = "trojan://pw@$host:443#$name"
    private fun row(l: String, name: String, cc: String?) = ConfigRow(l, "trojan", name, cc, null, 3)
    private fun cand(l: String, alive: Boolean, cc: String = "", hint: Boolean = false, prio: Int = 0) =
        Candidate(l, ConfigIdentity.key(l), 3, -1.0, 0, alive = alive, cc = cc, modeHint = hint, prio = prio)

    @Test
    fun optionsListAliveFirstOneServerPerHostAndMarkCurrent() {
        val a = link("A", "a.example"); val a2 = "vless://id@a.example:443?security=tls#A2"
        val b = link("B", "b.example"); val c = link("C", "c.example")
        val rows = listOf(row(a, "A", "DE"), row(a2, "A2", "DE"), row(b, "B", "NL"), row(c, "C", null))
        val ranked = listOf(cand(c, false), cand(a, true, "DE"), cand(a2, true, "DE"), cand(b, true, "NL"))
        val out = ServerChoice.options(ranked, rows, emptyMap(), emptyList(), currentLink = b)
        assertEquals(listOf("A", "B", "C"), out.map { it.title })
        assertTrue(out.first { it.title == "B" }.current)
        assertFalse(out.first { it.title == "A" }.current)
        assertEquals("Германия", out[0].place)
        assertEquals("страна неизвестна", out[2].place)
        assertFalse(out[2].checked)
    }

    @Test
    fun badServersAreHidden() {
        val a = link("A", "a.example")
        val bad = cand(a, true).copy(bad = true)
        assertTrue(ServerChoice.options(listOf(bad), listOf(row(a, "A", null)), emptyMap(), emptyList(), "").isEmpty())
    }

    @Test
    fun serverNamedForTheModeIsTriedBeforeHighPriorityForeign() {
        val wl = cand(link("[WL] Сервер 34", "wl.example"), true, hint = true)
        val hi = cand(link("Bulgaria #95", "bg.example"), true, prio = 1)
        val order = QuickOrder.order(Ranking.rank(listOf(hi, wl)), null, 2)
        assertEquals(wl.link, order[0])
    }

    @Test
    fun failoverGateIgnoresOldOfflineAndSettlesAfterModeSwitch() {
        assertTrue(FailoverGate.offlineRecently(1_000, 1_000 + 60_000))
        assertFalse(FailoverGate.offlineRecently(1_000, 1_000 + 300_000))
        assertFalse(FailoverGate.offlineRecently(0, 5_000))
        assertTrue(FailoverGate.modeSettling(10_000, 20_000))
        assertFalse(FailoverGate.modeSettling(10_000, 60_000))
        assertTrue(FailoverGate.modeOutOfSync(Mode.WHITELIST, Mode.BLACKLIST))
        assertFalse(FailoverGate.modeOutOfSync(Mode.WHITELIST, Mode.WHITELIST))
    }

    @Test
    fun recheckGuardDistrustsAllDeadAndNetworkChange() {
        assertTrue(RecheckGuard.suspicious(0, 677))
        assertFalse(RecheckGuard.suspicious(0, 10))
        assertFalse(RecheckGuard.suspicious(30, 677))
        assertTrue(RecheckGuard.networkChanged("Wi-Fi", "LTE"))
        assertFalse(RecheckGuard.networkChanged("Wi-Fi", "Wi-Fi"))
    }

    @Test fun appCellsShowMarkAndPercent() {
        val svc = listOf(CheckService("TG", "Telegram", "u"), CheckService("YT", "YouTube", "u"), CheckService("ST", "Steam", "u"))
        val check = CheckResult("2026-10-24", 120, mapOf("TG" to 200L, "YT" to -1L))
        val cells = ServerChoice.cells(check, svc) { sid -> if (sid == "TG") 90 else null }
        assertEquals(listOf("✓", "✗", "?"), cells.map { it.mark })
        assertEquals(listOf<Int?>(90, null, null), cells.map { it.percent })
        assertEquals("TG", cells[0].code)
    }

    @Test fun realExitCountryOverridesNameGuess() {
        val l = link("Server RU name", "a.example")
        val c = cand(l, alive = true, cc = "RU")
        val key = CheckStore.keyOf(l)
        val o = ServerChoice.options(listOf(c), listOf(row(l, "Server RU name", "RU")), emptyMap(), emptyList(), "", exitCc = { k -> if (k == key) "NL" else null })
        assertEquals("NL", o[0].cc)
        assertTrue(o[0].exitChecked)
        assertEquals("Нидерланды", o[0].place)
    }
}
