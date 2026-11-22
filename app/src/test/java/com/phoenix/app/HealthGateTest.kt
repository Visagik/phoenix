package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthGateTest {
    @Test
    fun settlingOnlyForAMinuteAfterNetworkShake() {
        val t = 1_000_000L
        assertTrue(HealthGate.settling(t + 17_000, t))
        assertTrue(HealthGate.settling(t + 59_999, t))
        assertFalse(HealthGate.settling(t + 60_000, t))
        assertFalse(HealthGate.settling(t, 0L)) // сеть ни разу не менялась
        assertFalse(HealthGate.settling(t - 1, t)) // часы ушли назад — не залипаем
    }

    @Test
    fun mobileNeedsThreeFailuresWifiTwo() {
        assertEquals(3, HealthGate.failuresNeeded(true))
        assertEquals(2, HealthGate.failuresNeeded(false))
    }

    @Test
    fun modeDetectionOnFirstFailureOnlyRightAfterNetworkChange() {
        assertEquals(1, HealthGate.detectAt(true))
        assertEquals(2, HealthGate.detectAt(false))
        val t = 5_000_000L
        assertTrue(HealthGate.recent(t + 100_000, t))
        assertFalse(HealthGate.recent(t + 120_000, t))
    }

    @Test
    fun earlyFallWithinTwoMinutesOfChoosing() {
        val t = 9_000_000L
        assertTrue(HealthGate.earlyFall(t + 44_000, t))
        assertFalse(HealthGate.earlyFall(t + 120_000, t))
        assertFalse(HealthGate.earlyFall(t + 10, 0L))
    }

    @Test
    fun earlyFallGivesHourQuarantineOtherwiseTenMinutes() {
        val now = 1_700_000_000_000L
        Quarantine.add("srvA", now, earlyFall = true)
        assertTrue(Quarantine.active("srvA", now + 59 * 60_000L))
        assertFalse(Quarantine.active("srvA", now + 61 * 60_000L))
        Quarantine.add("srvB", now)
        assertTrue(Quarantine.active("srvB", now + 9 * 60_000L))
        assertFalse(Quarantine.active("srvB", now + 11 * 60_000L))
    }

    @Test
    fun httpsGenerate204CountsAsAlive() {
        assertTrue(HealthVerdict.ok("https://www.gstatic.com/generate_204", 204))
        assertFalse(HealthVerdict.ok("https://www.gstatic.com/generate_204", 200))
    }

    @Test
    fun failoverBudgetCapsStagesByRemainingTime() {
        val t = 0L
        assertEquals(60_000L, FailoverBudget.totalMs(false, true))
        assertEquals(120_000L, FailoverBudget.totalMs(false, false))
        assertEquals(150_000L, FailoverBudget.totalMs(true, true))
        // первый этап взял 40 с из 60 — второму остаётся 20, а не желаемые 120
        assertEquals(20_000L, FailoverBudget.stageMs(40_000, t, 60_000, 120_000))
        // желаемое меньше остатка — берём желаемое
        assertEquals(30_000L, FailoverBudget.stageMs(0, t, 60_000, 30_000))
        // осталось меньше 5 с — этап пропускаем
        assertEquals(0L, FailoverBudget.stageMs(57_000, t, 60_000, 40_000))
        assertEquals(0L, FailoverBudget.stageMs(70_000, t, 60_000, 40_000))
    }

    @Test
    fun memGuardScalesParallelismByMemory() {
        assertEquals(16, MemGuard.scale(16, 150))
        assertEquals(8, MemGuard.scale(16, 200))
        assertEquals(4, MemGuard.scale(16, 260))
        assertEquals(16, MemGuard.scale(16, 0)) // не измерили — не трогаем
        assertEquals(2, MemGuard.scale(3, 210)) // слабый телефон: не меньше 2 на средней нагрузке
        assertEquals(1, MemGuard.scale(3, 300))
    }
}
