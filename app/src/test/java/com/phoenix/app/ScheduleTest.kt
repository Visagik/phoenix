package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScheduleTest {
    private fun at(h: Int, m: Int, day: Int = 14) = LocalDateTime.of(2026, 10, day, h, m)

    @Test
    fun nextRunIsTodayIfNotPassedElseTomorrow() {
        assertEquals(at(0, 5, 15), Schedule.nextRun(at(23, 0), 0, 5))
        assertEquals(at(0, 5, 15), Schedule.nextRun(at(0, 5), 0, 5)) // ровно сейчас — уже прошло
        assertEquals(at(3, 30), Schedule.nextRun(at(1, 0), 3, 30))
    }

    @Test
    fun catchUpWhenSlotMissedAndNothingDoneToday() {
        // телефон включили в 08:00, сбор назначен на 00:05, сегодняшнего файла нет, попыток не было
        assertTrue(Schedule.catchUpNeeded(at(8, 0), 0, 5, doneToday = false, lastAttemptMillis = 0, nowMillis = 1_000_000))
    }

    @Test
    fun noCatchUpWhenAlreadyDone() {
        assertFalse(Schedule.catchUpNeeded(at(8, 0), 0, 5, doneToday = true, lastAttemptMillis = 0, nowMillis = 1_000_000))
    }

    @Test
    fun noCatchUpBeforeSlotPlusGrace() {
        assertFalse(Schedule.catchUpNeeded(at(0, 10), 0, 5, false, 0, 1_000_000)) // будильник ещё может сработать сам
        assertTrue(Schedule.catchUpNeeded(at(0, 15), 0, 5, false, 0, 1_000_000))
        assertFalse(Schedule.catchUpNeeded(at(0, 1), 0, 5, false, 0, 1_000_000)) // слот ещё не наступил
    }

    @Test
    fun noRepeatedCatchUpWithinMinGap() {
        val now = 10_000_000_000L
        assertFalse(Schedule.catchUpNeeded(at(8, 0), 0, 5, false, now - 10 * 60 * 1000, now))
        assertTrue(Schedule.catchUpNeeded(at(8, 0), 0, 5, false, now - 31 * 60 * 1000, now))
    }

    @Test
    fun graceCrossingMidnightNeverCatchesUpSameDay() {
        // 23:55 + 10 мин = 00:05 следующего дня: такой слот считаем не наступившим (его закрывает будильник)
        assertFalse(Schedule.catchUpNeeded(at(23, 58), 23, 55, false, 0, 1_000_000))
    }
}
