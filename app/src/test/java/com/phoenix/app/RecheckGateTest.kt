package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Н4/Р4: все 8 сочетаний (ручной, уже идёт, настройка). */
class RecheckGateTest {
    private fun a(manual: Boolean, running: Boolean, auto: Boolean) = RecheckGate.afterCollect(manual, running, auto)
    private val M = RecheckGate.After.MANUAL_RECHECK
    private val D = RecheckGate.After.AUTO_IF_DUE
    private val T = RecheckGate.After.TARGETS_ONLY

    @Test fun manualNotRunning() { assertEquals(M, a(true, false, false)); assertEquals(M, a(true, false, true)) }
    @Test fun manualWhileRunning() { assertEquals(T, a(true, true, false)); assertEquals(T, a(true, true, true)) }
    @Test fun autoOnNotRunning() { assertEquals(D, a(false, false, true)) }
    @Test fun autoOffNotRunning() { assertEquals(T, a(false, false, false)) }
    @Test fun autoWhileRunning() { assertEquals(T, a(false, true, true)); assertEquals(T, a(false, true, false)) }
}
