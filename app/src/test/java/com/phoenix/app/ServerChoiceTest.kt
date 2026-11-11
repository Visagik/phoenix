package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerChoiceTest {
    private fun trojan(name: String, host: String) = "trojan://pw@$host:443#$name"
    private fun vless(name: String, host: String) = "vless://id@$host:443?security=tls#$name"
    private fun cand(link: String, days: Int = 3, mbps: Double = -1.0, caps: Int = 0): Candidate =
        Candidate(link, ConfigIdentity.key(link), days, mbps, 0, caps)

    @Test
    fun sameServerDifferentProtocolsCollapseToBest() {
        val a = cand(vless("Alpha Server", "same.example"), mbps = 5.0, caps = 2)
        val b = cand(trojan("Alpha Server T", "same.example"), mbps = 50.0, caps = 1)
        val c = cand(trojan("Gamma Server", "other.example"), mbps = 1.0, caps = 0)
        val out = Ranking.distinctServers(Ranking.rank(listOf(b, a, c)))
        assertEquals(2, out.size)
        // больше возможностей — выше, даже если скорость меньше
        assertEquals(a.link, out[0].link)
        assertEquals(c.link, out[1].link)
    }

    @Test
    fun failoverSkipsCurrentAndTriedHosts() {
        val cur = cand(vless("Current Server", "cur.example"))
        val sibling = cand(trojan("Current Server T", "cur.example"), mbps = 99.0)
        val triedOne = cand(trojan("Tried Server", "tried.example"), mbps = 50.0)
        val triedSibling = cand(vless("Tried Server V", "tried.example"), mbps = 40.0)
        val fresh = cand(trojan("Fresh Server", "fresh.example"), mbps = 1.0)
        val order = Ranking.failoverOrder(
            listOf(cur, sibling, triedOne, triedSibling, fresh),
            tried = setOf(triedOne.identity), currentIdentity = cur.identity,
            currentHost = "cur.example", triedHosts = setOf("tried.example"),
        )
        assertEquals(listOf(fresh.link), order.map { it.link })
    }

    @Test
    fun verifierKeepsOnlyDistinctHosts() {
        val target = Target.app("p.q", "PQ", "https://x.test/")
        val cands = listOf(
            cand(vless("One Server", "a.example"), mbps = 9.0),
            cand(trojan("One Server T", "a.example"), mbps = 8.0),
            cand(trojan("Two Server", "b.example"), mbps = 7.0),
            cand(trojan("Three Server", "c.example"), mbps = 6.0),
        )
        val servers = runBlocking { TargetVerifier({ _, _ -> 5 }, parallel = 1, keep = 3).verify(target, cands) }
        assertEquals(3, servers.size)
        assertEquals(3, servers.map { ConfigIdentity.host(it) }.toSet().size)
    }

    @Test
    fun deadServerIsNotProbedByCore() {
        var probes = 0
        val checker = ServiceChecker({ _, _ -> probes++; 10L }, reach = { h, _ -> if (h == "dead.example") null else "1.2.3.4" })
        val dead = checker.checkOne(trojan("Dead Server", "dead.example"), CheckServices.ALL, "2026-10-15")
        assertTrue(dead.dead)
        assertEquals(0, probes)
        val alive = checker.checkOne(trojan("Alive Server", "alive.example"), CheckServices.ALL.take(1), "2026-10-15")
        assertFalse(alive.dead)
        assertEquals(2, probes) // контроль + один сервис
    }

    @Test
    fun domainServerGetsResolvedHostInConfig() {
        val seen = ArrayList<String>()
        val checker = ServiceChecker({ cfg, _ -> seen += cfg; 10L }, reach = { _, _ -> "203.0.113.7" })
        checker.checkOne(trojan("Domain Server", "node.example.com"), emptyList(), "2026-10-15")
        assertTrue(seen.single().contains("\"hosts\""))
        assertTrue(seen.single().contains("\"node.example.com\":\"203.0.113.7\""))
        // IP-адрес в hosts не нужен
        assertTrue(ServerHosts.hostsFor("1.2.3.4", "1.2.3.4").isEmpty())
    }

    @Test
    fun autoPolicyResolvesByCapabilities() {
        val app = Target.app("p.q", "PQ", "https://x.test/")
        val site = Target.site("example.org")!!
        assertEquals(Policy.PROXY, app.resolve(Policy.AUTO, finderOk = true, usageOk = true))
        assertEquals(Policy.SWITCH, app.resolve(Policy.AUTO, finderOk = false, usageOk = true))
        assertEquals(Policy.PROXY, app.resolve(Policy.AUTO, finderOk = false, usageOk = false))
        assertEquals(Policy.PROXY, site.resolve(Policy.AUTO, finderOk = false, usageOk = true))
        assertEquals(Policy.SWITCH, app.copy(policy = Policy.SWITCH).resolve(Policy.AUTO, true, true))
    }
}

class SiblingCheckTest {
    private fun vless(name: String, host: String) = "vless://id@$host:443?security=reality&pbk=k&flow=xtls-rprx-vision#$name"
    private fun trojan(name: String, host: String) = "trojan://pw@$host:443#$name"

    @Test
    fun checksOnlyFirstAliveSiblingByPriority() {
        val checked = ArrayList<String>()
        val checker = ServiceChecker({ cfg, _ -> synchronized(checked) { checked += cfg }; 10L }, parallel = 1)
        val v = vless("Same Server V", "same.example")
        val t = trojan("Same Server T", "same.example")
        val other = trojan("Other Server", "other.example")
        val res = runBlocking { checker.check(listOf(t, v, other), emptyList(), "2026-10-15") }
        assertEquals(2, res.size) // один с same.example и один с other.example
        assertTrue(res.containsKey(CheckStore.keyOf(v))) // приоритетнее (reality+vision)
        assertFalse(res.containsKey(CheckStore.keyOf(t)))
    }

    @Test
    fun fallsBackToNextSiblingWhenFirstIsDead() {
        val checker = ServiceChecker({ cfg, _ -> if (cfg.contains("\"vless\"")) -1L else 10L }, parallel = 1)
        val v = vless("Same Server V", "same.example")
        val t = trojan("Same Server T", "same.example")
        val res = runBlocking { checker.check(listOf(v, t), emptyList(), "2026-10-15") }
        assertEquals(2, res.size)
        assertTrue(res.getValue(CheckStore.keyOf(v)).dead)
        assertFalse(res.getValue(CheckStore.keyOf(t)).dead)
    }
}

class Ipv6ConfigTest {
    private val link = "trojan://pw@1.2.3.4:443#Some Server"

    @Test
    fun blockModeAddsIpv6BlockRuleAfterPrivateNets() {
        val cfg = XrayConfig.vpnConfig(link, XrayOptions(ipv6 = Ipv6Mode.BLOCK))
        assertTrue(cfg.contains("\"::/0\""))
        assertTrue(cfg.indexOf("fe80::/10") < cfg.indexOf("\"::/0\""))
    }

    @Test
    fun otherModesDoNotBlockIpv6() {
        assertFalse(XrayConfig.vpnConfig(link, XrayOptions(ipv6 = Ipv6Mode.TUNNEL)).contains("\"::/0\""))
        assertFalse(XrayConfig.vpnConfig(link, XrayOptions(ipv6 = Ipv6Mode.OFF)).contains("\"::/0\""))
    }
}
