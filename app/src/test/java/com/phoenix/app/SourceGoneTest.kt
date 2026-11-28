package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGoneTest {
    private fun st(status: String, date: String) = SourceState(true, 1, date, status, 0)
    private val today = "2026-11-24"
    private val yesterday = "2026-11-23"

    @Test fun twoDaysOf404Remove() = assertTrue(SourcePolicy.definitiveGone(st("FAIL:404", yesterday), 404, today))

    @Test fun twice404InOneDayDoesNot() = assertFalse(SourcePolicy.definitiveGone(st("FAIL:404", today), 404, today))

    @Test fun otherCodesDoNotRemove() {
        for (c in listOf(403, 401, 451, 0, 429, 500, 503)) {
            assertFalse("код $c", SourcePolicy.definitiveGone(st("FAIL:$c", yesterday), c, today))
        }
    }

    @Test fun answerAfter404Does() {
        assertFalse(SourcePolicy.definitiveGone(st("FAIL:404", yesterday), 200, today))
        assertFalse(SourcePolicy.definitiveGone(st("OK", yesterday), 404, today))
        assertFalse(SourcePolicy.definitiveGone(SourceState(), 404, today))
    }

    @Test fun mixed410And404Remove() {
        assertTrue(SourcePolicy.definitiveGone(st("FAIL:410", yesterday), 404, today))
        assertTrue(SourcePolicy.definitiveGone(st("FAIL:404", yesterday), 410, today))
    }

    @Test fun protectedSourceIsWarnedNotRemoved() {
        val plan = SourcePolicy.planGone(listOf(
            SourcePolicy.Gone("https://mine/a", 404, yesterday, true),
            SourcePolicy.Gone("https://builtin/b", 404, yesterday, false),
        ))
        assertEquals(listOf("https://builtin/b"), plan.remove.map { it.url })
        assertEquals(listOf("https://mine/a"), plan.warn.map { it.url })
        assertEquals(0, plan.postponed)
    }

    @Test fun sixCandidatesRemoveFive() {
        val six = (1..6).map { SourcePolicy.Gone("https://x/$it", 404, yesterday, false) }
        val plan = SourcePolicy.planGone(six)
        assertEquals(5, plan.remove.size)
        assertEquals(1, plan.postponed)
        assertEquals("https://x/6", six.last().url)
        assertFalse(plan.remove.any { it.url == "https://x/6" })
    }

    @Test fun protectedDoNotUseUpTheLimit() {
        val list = (1..3).map { SourcePolicy.Gone("https://p/$it", 404, yesterday, true) } +
            (1..5).map { SourcePolicy.Gone("https://x/$it", 404, yesterday, false) }
        val plan = SourcePolicy.planGone(list)
        assertEquals(5, plan.remove.size)
        assertEquals(3, plan.warn.size)
        assertEquals(0, plan.postponed)
    }

    // --- код 0 не стирает запомненный 404/410 (журнал 24.11: Wi-Fi даёт 404, мобильный VPN — код 0) ---

    private val day1 = "2026-11-22"
    private val day2 = "2026-11-23"
    private val day3 = "2026-11-24"

    @Test fun zeroBetweenTwo404StillRemoves() {
        var s = SourceState()
        s = SourcePolicy.onFailure(s, 404, day1)
        assertFalse(SourcePolicy.definitiveGone(SourceState(), 404, day1))
        s = SourcePolicy.onFailure(s, 0, day2)
        assertEquals("FAIL:404@$day1", s.status) // Д4: дата окончательного ответа — в статусе
        assertEquals(day2, s.lastDate)
        assertEquals(2, s.fails)
        assertTrue(SourcePolicy.definitiveGone(s, 404, day3))
    }

    @Test fun successAfterZeroForgets404() {
        var s = SourcePolicy.onFailure(SourceState(), 404, day1)
        s = SourcePolicy.onFailure(s, 0, day2)
        s = SourcePolicy.onSuccess(day3)
        assertEquals("OK", s.status)
        assertFalse(SourcePolicy.definitiveGone(s, 404, "2026-11-25"))
    }

    @Test fun otherNonFinalCodesAlsoKeepTheMemory() {
        for (c in listOf(0, 403, 429, 500, 503, 200)) {
            val s = SourcePolicy.onFailure(SourcePolicy.onFailure(SourceState(), 410, day1), c, day2)
            assertEquals("код $c", "FAIL:410@$day1", s.status)
            assertEquals("код $c", day2, s.lastDate)
            assertTrue("код $c", SourcePolicy.definitiveGone(s, 410, day3))
        }
    }

    @Test fun failureCounterAndBlockingWorkAsBefore() {
        var s = SourcePolicy.onFailure(SourceState(), 404, day1)
        repeat(3) { s = SourcePolicy.onFailure(s, 0, day2) }
        assertEquals(4, s.fails)
        assertTrue(s.active)
        s = SourcePolicy.onFailure(s, 0, day2)
        assertEquals(5, s.fails)
        assertFalse(s.active)
    }

    @Test fun zeroWithoutMemoryRecordsTodayAsBefore() {
        val s = SourcePolicy.onFailure(SourceState(true, 1, day1, "FAIL:0", 0), 0, day2)
        assertEquals("FAIL:0", s.status)
        assertEquals(day2, s.lastDate)
        val s2 = SourcePolicy.onFailure(SourceState(true, 1, day1, "OK", 0), 503, day2)
        assertEquals(day2, s2.lastDate)
    }

    @Test fun newDefinitiveAnswerReplacesTheRecord() {
        val s = SourcePolicy.onFailure(SourceState(true, 2, day1, "FAIL:404", 0), 410, day2)
        assertEquals("FAIL:410@$day2", s.status)
        assertEquals(day2, s.lastDate)
    }
}

class SourceGoneDateTest {
    private val d1 = "2026-11-21"; private val d3 = "2026-11-23"; private val d4 = "2026-11-24"

    @Test fun code0AfterRemembered404KeepsDateInStatusAndMovesLastDate() {
        val s1 = SourcePolicy.onFailure(SourceState(), 404, d1)
        val s3 = SourcePolicy.onFailure(s1, 0, d3)
        assertEquals(d3, s3.lastDate)
        assertEquals("FAIL:404@$d1", s3.status)
    }

    @Test fun secondDay404AfterZeroRemoves() {
        val s1 = SourcePolicy.onFailure(SourceState(), 404, d1)
        val s3 = SourcePolicy.onFailure(s1, 0, d3)
        assertTrue(SourcePolicy.definitiveGone(s3, 404, d4))
        assertEquals(d1, SourcePolicy.definitiveDate(s3)) // в причину пойдут даты 1 и 4
    }

    @Test fun sameDay404AfterRememberedDateDoesNot() {
        val s1 = SourcePolicy.onFailure(SourceState(), 404, d1)
        assertFalse(SourcePolicy.definitiveGone(s1, 404, d1))
    }

    @Test fun oldFormatStatusUsesLastDate() {
        val old = SourceState(true, 1, d1, "FAIL:404", 0)
        assertTrue(SourcePolicy.definitiveGone(old, 404, d4))
        assertEquals(d1, SourcePolicy.definitiveDate(old))
        val after0 = SourcePolicy.onFailure(old, 0, d3)
        assertEquals("FAIL:404@$d1", after0.status)
        assertEquals(d3, after0.lastDate)
    }

    @Test fun reactivationCountsFromLastFailureNotFrom404() {
        val s1 = SourcePolicy.onFailure(SourceState(), 404, d1).copy(active = false)
        val s3 = SourcePolicy.onFailure(s1, 0, d3).copy(active = false)
        assertFalse(SourcePolicy.shouldReactivate(s3, java.time.LocalDate.parse("2026-11-29"))) // 6 дней от d3
        assertTrue(SourcePolicy.shouldReactivate(s3, java.time.LocalDate.parse("2026-11-30")))
    }
}
