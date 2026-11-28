package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сценарий (журнал 24.11): DeepSeek закрыт напрямую только на мобильной сети. Дома на Wi-Fi подбор пустил цель «мимо VPN»,
 * а на мобильной она продолжала идти напрямую и не открывалась. «Открывается напрямую» теперь помнится по сетям (DirectLearn),
 * и в сети, где цель не открывается, своего прямого маршрута у неё нет.
 */
class DirectTargetPerNetworkTest {
    private val wifi = "wifi:Home"
    private val mobile = "mobile:megafon"
    private val deepseek = Target("APP:com.deepseek.chat", TargetKind.APP, "com.deepseek.chat", "DeepSeek", "https://chat.deepseek.com/", Policy.PROXY, listOf(Target.DIRECT))
    private val key = DirectLearn.targetKey(deepseek.id)

    private fun record(map: Map<String, LearnEntry>, net: String, ok: Boolean, n: Int = 1): Map<String, LearnEntry> {
        var m = map
        repeat(n) { m = DirectLearn.record(m, net, key, ok, 1_000L) }
        return m
    }

    private fun routes(blocked: Set<String>) =
        PlanBuilder.routes("vless://m@main.test:443#M", listOf(deepseek), Policy.PROXY, directBlocked = blocked) { "10" }

    @Test fun directRouteIsKeptWhereTheTargetOpens() {
        val r = routes(emptySet())
        assertEquals(1, r.size)
        assertTrue(r[0].direct)
    }

    @Test fun noDirectRouteWhereTheTargetIsClosedDirectly() {
        assertTrue(routes(setOf(deepseek.id)).isEmpty())
    }

    @Test fun twoFailuresOnMobileBlockOnlyMobile() {
        var m = record(emptyMap(), wifi, ok = true, n = 3)
        m = record(m, mobile, ok = false, n = 2)
        assertEquals(setOf(deepseek.id), DirectLearn.blockedTargets(m, mobile))
        assertEquals(emptySet<String>(), DirectLearn.blockedTargets(m, wifi))
    }

    @Test fun oneFailureIsNotYetABlock() {
        assertEquals(emptySet<String>(), DirectLearn.blockedTargets(record(emptyMap(), mobile, ok = false), mobile))
    }

    @Test fun successWhereBlockedLiftsTheBlock() {
        var m = record(emptyMap(), mobile, ok = false, n = 2)
        m = record(m, mobile, ok = true)
        assertFalse(deepseek.id in DirectLearn.blockedTargets(m, mobile))
    }

    @Test fun catalogServicesAreNotMixedWithTargets() {
        val m = DirectLearn.record(DirectLearn.record(emptyMap(), mobile, "sber", false, 1L), mobile, "sber", false, 2L)
        assertTrue(DirectLearn.blockedIds(m, mobile).contains("sber"))
        assertEquals(emptySet<String>(), DirectLearn.blockedTargets(m, mobile))
    }

    @Test fun realServersDoNotContainTheDirectMark() {
        val t = deepseek.copy(servers = listOf(Target.DIRECT, "vless://a@h:1#A"))
        assertEquals(listOf("vless://a@h:1#A"), t.realServers)
        assertTrue(deepseek.realServers.isEmpty())
    }
}
