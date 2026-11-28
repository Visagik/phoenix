package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckUpdateTest {
    private val today = "2026-11-28"

    @Test fun liveTodayKeepsServicesButTakesFreshDelay() {
        val prev = CheckResult(today, 300, mapOf("tg" to 400L), mbps = 12.5, host = "a:1")
        val r = CheckUpdate.forLive(prev, 4000, today, "a:1")!!
        assertEquals(4000L, r.baselineMs)
        assertEquals(mapOf("tg" to 400L), r.services)
        assertEquals(12.5, r.mbps, 0.0)
    }

    @Test fun hostChangeStartsFromScratch() {
        val prev = CheckResult(today, 300, mapOf("tg" to 400L), mbps = 9.0, host = "a:1")
        val r = CheckUpdate.forLive(prev, 300, today, "b:2")!!
        assertEquals("b:2", r.host)
        assertEquals(emptyMap<String, Long>(), r.services)
        assertEquals(CheckResult(today, 300, emptyMap(), host = "b:2"), r)
    }

    @Test fun emptyOldHostKeepsServices() {
        val prev = CheckResult(today, 300, mapOf("tg" to 400L), host = "")
        val r = CheckUpdate.forLive(prev, 500, today, "b:2")!!
        assertEquals(mapOf("tg" to 400L), r.services)
        assertEquals("b:2", r.host)
        assertEquals(500L, r.baselineMs)
    }

    @Test fun sameValuesNeedNoWrite() = assertNull(CheckUpdate.forLive(CheckResult(today, 300, emptyMap(), host = "a:1"), 300, today, "a:1"))

    @Test fun newDeadOrOldStartFromScratch() {
        assertEquals(CheckResult(today, 100, emptyMap(), host = "h"), CheckUpdate.forLive(null, 100, today, "h"))
        assertEquals(CheckResult(today, 100, emptyMap(), host = "h"), CheckUpdate.forLive(CheckResult(today, -1, emptyMap()), 100, today, "h"))
        assertEquals(CheckResult(today, 100, emptyMap(), host = "h"), CheckUpdate.forLive(CheckResult("2026-11-27", 50, mapOf("tg" to 1L)), 100, today, "h"))
    }
}
