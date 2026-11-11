package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class Release3929Test {
    @Test fun gatewayCalibrationDisablesOnSilentGateway() {
        val g = GatewayLoad()
        repeat(3) { assertEquals(GatewayLoad.Verdict.OK, g.feed(-1)) }
        assertTrue(g.disabled)
        assertEquals(GatewayLoad.Verdict.OK, g.feed(-1))
    }

    @Test fun gatewayBusyOnSlowOrTimeout() {
        val g = GatewayLoad()
        g.feed(3); g.feed(2); g.feed(4)
        assertEquals(GatewayLoad.Verdict.OK, g.feed(6))
        assertEquals(GatewayLoad.Verdict.BUSY, g.feed(400))
        assertEquals(GatewayLoad.Verdict.BUSY, g.feed(-1))
        assertFalse(g.disabled)
    }

    @Test fun gateSlowStartGrowsAndBacksOff() {
        var t = 0L
        val gate = AdaptiveGate(start = 8, max = 48, now = { t }, random = { 0.0 })
        assertEquals(8, gate.limit)
        gate.ok(); gate.ok()
        assertEquals(12, gate.limit)
        gate.busy()
        assertEquals(6, gate.limit)
        gate.busy()
        assertEquals(4, gate.limit)
        assertEquals(2, gate.throttled)
        // пауза: второй busy подряд = 2 с
        t = 1_500
        var ran = false
        t = 10_000
        runBlocking { gate.run { ran = true } }
        assertTrue(ran)
    }

    @Test fun gateNeverExceedsMax() {
        val gate = AdaptiveGate(start = 100, max = 16)
        assertEquals(16, gate.limit)
        repeat(20) { gate.ok() }
        assertEquals(16, gate.limit)
    }

    @Test fun reasonText() {
        assertEquals("вы нажали «Сменить сервер»", SwitchReason.describe("x", forced = true))
        assertEquals("сервер не отвечает (таймаут)", SwitchReason.describe("gstatic.com: SocketTimeoutException: timeout"))
        assertEquals("сервер отклонил соединение", SwitchReason.describe("a: Connection refused"))
        assertEquals("сайты отвечают ошибкой HTTP 503", SwitchReason.describe("a.com: HTTP 503"))
        assertEquals("нет ответа через туннель", SwitchReason.describe(""))
        assertEquals("сервер сменён — прежний: соединение сброшено", SwitchReason.note("сервер сменён", "соединение сброшено"))
    }

    @Test fun timelineKeepsRecentImportantLines() {
        val zone = ZoneId.of("UTC")
        val now = OffsetDateTime.parse("2026-10-26T12:00:00+00:00")
        val lines = listOf(
            "2026-10-26T11:30:00+00:00 FAILOVER: старое",
            "2026-10-26T11:50:10+00:00 HEALTH: сервер не отвечает",
            "2026-10-26T11:50:11+00:00 DEBUG: шум",
            "2026-10-26T11:50:12+00:00 FAILOVER: [A] не работает, ищу среди 100",
            "2026-10-26T11:50:12+00:00 FAILOVER: [A] не работает, ищу среди 100",
            "2026-10-26T11:50:20+00:00 VPN: переключился на запасной [B]",
        )
        val items = EventTimeline.build(lines, now, zone)
        assertEquals(3, items.size)
        assertEquals("11:50:10", items[0].time)
        assertTrue(items.last().text.startsWith("VPN:"))
    }

    @Test fun protocolKinds() {
        assertEquals("reality", ProtocolLearning.kindOf("vless://u@h:443?security=reality&pbk=x"))
        assertEquals("ws", ProtocolLearning.kindOf("vless://u@h:443?type=ws&security=tls"))
        assertEquals("hysteria2", ProtocolLearning.kindOf("hy2://p@h:443"))
        assertEquals("trojan", ProtocolLearning.kindOf("trojan://p@h:443"))
    }

    @Test fun protocolBucketsComparePeers() {
        val st = { ok: Int, fail: Int -> HistStat(ok, fail, 0L) }
        val rates = ProtocolLearning.rates(listOf(
            "vless://u@h:443?security=reality&pbk=x" to st(30, 5),
            "trojan://p@h:443" to st(1, 14),
            "ss://x@h:1" to st(2, 1),
        ))
        assertEquals(0, ProtocolLearning.bucket("reality", rates))
        assertEquals(2, ProtocolLearning.bucket("trojan", rates))
        assertEquals(1, ProtocolLearning.bucket("ss", rates))
        assertEquals(1, ProtocolLearning.bucket("vmess", rates))
        // если известен только один тип — различий нет
        val one = ProtocolLearning.rates(listOf("trojan://p@h:443" to st(1, 14)))
        assertEquals(1, ProtocolLearning.bucket("trojan", one))
        assertTrue(ProtocolLearning.summary(rates).startsWith("Reality"))
    }

    @Test fun lowBatteryPace() {
        assertTrue(HealthPace.lowBattery(15, false, false))
        assertFalse(HealthPace.lowBattery(15, true, false))
        assertTrue(HealthPace.lowBattery(80, false, true))
        assertFalse(HealthPace.lowBattery(-1, false, false))
        assertEquals(HealthPace.LOW_BATTERY_MS, HealthPace.nextDelay(true, 0, 0, lowBattery = true))
        assertEquals(HealthPace.BASE_MS, HealthPace.nextDelay(true, 1, 0, lowBattery = true))
        assertEquals(HealthPace.SCREEN_OFF_MS, HealthPace.nextDelay(false, 0, 0, lowBattery = true))
    }
}
