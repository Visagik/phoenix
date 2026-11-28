package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** С1 (начало простоя после успешного подключения), С2 (перезагрузка ядра только если сервер не сменили), С3 (параллельная проверка связи). */
class Stage1OutageTest {
    @Test fun pickTakesLaterConnect() {
        // неудача в 10 000, подключение в 50 000, теперь 100 000: простой считаем с подключения
        assertEquals(50_000L, OutageStart.pick(100_000L, 10_000L, 50_000L))
    }
    @Test fun pickKeepsFailSinceWhenConnectEarlier() {
        assertEquals(10_000L, OutageStart.pick(100_000L, 10_000L, 5_000L))
    }
    @Test fun twoOutagesDoNotOverlap() {
        val first = OutageStart.pick(100_000L, 90_000L, 0L)
        val second = OutageStart.pick(400_000L, 90_000L, 300_000L)
        assertTrue(second >= 300_000L && second > first)
    }
    @Test fun netKindTitle() { assertEquals("смена сети", DowntimeStats.kindTitle(DowntimeStats.NET)) }

    @Test fun reloadOnlyIfNothingChanged() {
        assertTrue(NetReload.needed(5, 5, false))
        assertFalse(NetReload.needed(5, 6, false))
        assertFalse(NetReload.needed(5, 5, true))
    }

    @Test fun anyOkWins() {
        val a = listOf(HealthAnswer(1, false, "b: таймаут"), HealthAnswer(0, true, ""), HealthAnswer(2, false, "c: HTTP 500"))
        assertTrue(HealthSummary.ok(a)); assertEquals("", HealthSummary.detail(a))
    }
    @Test fun allFailGivesDetailPerUrlInListOrder() {
        val a = listOf(HealthAnswer(2, false, "c: x"), HealthAnswer(0, false, "a: y"), HealthAnswer(1, false, "b: z"))
        assertFalse(HealthSummary.ok(a)); assertEquals("a: y; b: z; c: x", HealthSummary.detail(a))
    }
    @Test fun measureAheadOnRightFailure() {
        assertEquals(2, HealthGate.measureAheadAt(true))
        assertEquals(1, HealthGate.measureAheadAt(false))
    }
}
