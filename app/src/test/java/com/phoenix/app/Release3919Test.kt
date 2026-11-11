package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3919Test {
    private fun link(i: Int) = "trojan://pw@10.0.${i / 250}.${i % 250 + 1}:443#Server number $i"
    private fun cand(i: Int, rel: Double = 0.5, hint: Boolean = false) =
        Candidate(link(i), ConfigIdentity.key(link(i)), 0, -1.0, 0, rel = rel, modeHint = hint, alive = true)

    @Test fun searchStopsWhenNothingOpensTcp() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val s = ServerSearch(reach = { _, _ -> calls.incrementAndGet(); null }, probe = { _, _ -> -1 }, tcpParallel = 4, maxCandidates = 2000)
        runBlocking { s.find((0 until 1000).map { cand(it) }, want = 3, budgetMs = 20_000) }
        assertTrue(s.suspectedOffline)
        assertTrue("проверено ${calls.get()}", calls.get() < 400)
    }

    @Test fun searchNotOfflineWhenSomeTcpOpens() {
        val s = ServerSearch(reach = { h, _ -> if (h.endsWith(".5")) h else null }, probe = { _, _ -> -1 }, tcpParallel = 4, maxCandidates = 600)
        runBlocking { s.find((0 until 300).map { cand(it) }, want = 3, budgetMs = 20_000) }
        assertFalse(s.suspectedOffline)
    }

    @Test fun provenServersGoFirstInQuickOrder() {
        val ranked = listOf(cand(1, hint = true), cand(2), cand(3, rel = 0.8), cand(4, rel = 0.75))
        val order = QuickOrder.order(ranked, null, 4)
        assertEquals(listOf(link(3), link(4), link(1), link(2)), order)
    }

    private val main = "trojan://pw@main.example:443#Main"

    @Test fun directTargetBecomesDirectRoute() {
        val t = Target.app("com.deepseek.chat", "DeepSeek", "https://chat.deepseek.com/").copy(servers = listOf(Target.DIRECT))
        assertTrue(t.isDirect)
        val routes = PlanBuilder.routes(main, listOf(t), Policy.PROXY) { "10200" }
        assertEquals(1, routes.size)
        assertTrue(routes[0].direct)
        assertEquals(listOf("10200"), routes[0].uids)
    }

    @Test fun directRouteGoesToDirectOutboundWithoutNewOutbound() {
        val json = XrayConfig.vpnConfig(main, XrayOptions(routes = listOf(AppRoute("t0", "", uids = listOf("10200"), direct = true))))
        assertTrue(json.contains("\"process\""))
        assertFalse(json.contains("\"t0\""))
        val rule = json.substringAfter("\"process\"")
        assertTrue(rule.contains("\"outboundTag\":\"direct\""))
    }

    @Test fun autoSkippedForRegionGatedOnly() {
        assertTrue(GeoGate.applies("https://gemini.google.com/"))
        assertFalse(GeoGate.applies("https://store.steampowered.com/"))
    }
}
