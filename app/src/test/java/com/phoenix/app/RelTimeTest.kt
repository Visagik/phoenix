package com.phoenix.app

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RelTimeTest {
    private val z = ZoneId.of("Europe/Moscow")
    private fun ms(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, z).toInstant().toEpochMilli()
    private val now = ms(14, 23, 40)

    @Test fun sameDayYesterdayTomorrow() {
        assertEquals("сегодня 05:07", RelTime.format(ms(14, 5, 7), now, z))
        assertEquals("вчера 09:30", RelTime.format(ms(13, 9, 30), now, z))
        assertEquals("завтра 07:00", RelTime.format(ms(15, 7, 0), now, z))
    }

    @Test fun otherDaysUseDate() {
        assertEquals("10.11 18:40", RelTime.format(ms(10, 18, 40), now, z))
        assertEquals("20.11 00:05", RelTime.format(ms(20, 0, 5), now, z))
    }

    @Test fun dayBoundaryIsLocal() {
        assertEquals("завтра 00:01", RelTime.format(ms(15, 0, 1), ms(14, 23, 59), z))
    }
}
