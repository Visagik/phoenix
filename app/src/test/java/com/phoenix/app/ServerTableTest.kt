package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTableTest {
    private fun e(
        n: String, cc: String? = "DE", proto: String = "vless", days: Int = 1, ping: Long? = null, mbps: Double? = null,
        ok: Int = 0, partial: Int = 0, blocked: Int = 0, checked: String = "", dead: Boolean = false,
    ) = ServerEntry("vless://$n", n, proto, false, cc, days, ping, mbps, ok, partial, blocked, 0, checked, dead)

    private val a = e("a", ping = 300, mbps = 5.0, ok = 3, checked = "2026-11-10", days = 5)
    private val b = e("b", cc = "NL", ping = 100, mbps = 20.0, ok = 5, checked = "2026-11-11", days = 1)
    private val c = e("c", cc = "FI", proto = "trojan", ping = null, checked = "", days = 9)
    private val d = e("d", cc = null, ping = -1, checked = "2026-11-09", dead = true)
    private val all = listOf(a, b, c, d)

    private fun names(l: List<ServerEntry>) = l.joinToString("") { it.name }

    @Test fun bestPutsAliveFirstAndDeadLast() {
        assertEquals("bacd", names(ServerTable.sort(all, ServerSort.BEST)))
    }

    @Test fun pingAscendingUnknownLast() {
        assertEquals("bacd", names(ServerTable.sort(all, ServerSort.PING)))
    }

    @Test fun speedDescendingUnknownLastEvenWhenReversed() {
        assertEquals("bacd", names(ServerTable.sort(all, ServerSort.SPEED)))
        val rev = ServerTable.sort(all, ServerSort.SPEED, desc = false)
        assertEquals("abcd", names(rev))
    }

    @Test fun daysDescending() {
        assertEquals("cabd", names(ServerTable.sort(all, ServerSort.DAYS)))
    }

    @Test fun checkedNewestFirstUncheckedLast() {
        assertEquals("badc", names(ServerTable.sort(all, ServerSort.CHECKED)))
    }

    @Test fun countryNullLast() {
        assertEquals("acbd", names(ServerTable.sort(all, ServerSort.COUNTRY)))
    }

    @Test fun filters() {
        assertEquals("ab", names(ServerTable.apply(all, ServerFilter(onlyAlive = true), ServerSort.NAME)))
        assertEquals("ac", names(ServerTable.apply(all, ServerFilter(onlyStable = true), ServerSort.NAME)))
        assertEquals("c", names(ServerTable.apply(all, ServerFilter(protocol = "TROJAN"), ServerSort.NAME)))
        assertEquals("b", names(ServerTable.apply(all, ServerFilter(country = "nl"), ServerSort.NAME)))
        assertEquals("b", names(ServerTable.apply(all, ServerFilter(minOk = 4), ServerSort.NAME)))
        assertEquals("b", names(ServerTable.apply(all, ServerFilter(query = "NL"), ServerSort.NAME)))
        assertEquals("abd", names(ServerTable.apply(all, ServerFilter(onlyChecked = true), ServerSort.NAME)))
    }

    @Test fun countriesAndProtocols() {
        assertEquals(listOf("DE", "??", "FI", "NL"), ServerTable.countries(all + e("x")))
        assertEquals(listOf("trojan", "vless"), ServerTable.protocols(all))
    }

    @Test fun summaryAndMetrics() {
        assertEquals("Всего 4 · проверено 3 · живых 2", ServerTable.summary(all))
        val m = ServerTable.metrics(a)
        assertTrue(m.contains("300 мс") && m.contains("5.0 Мбит/с") && m.contains("11-10"))
        assertTrue(ServerTable.metrics(d).contains("не отвечает"))
    }
}
