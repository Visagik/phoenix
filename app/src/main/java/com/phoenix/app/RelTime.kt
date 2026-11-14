package com.phoenix.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Короткая дата для экрана: «сегодня 14:05», «вчера 09:30», «завтра 07:00», иначе «12.11 18:40». */
object RelTime {
    fun format(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), t.toLocalDate())
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when (days) {
            0L -> tr("сегодня {}", hm)
            -1L -> tr("вчера {}", hm)
            1L -> tr("завтра {}", hm)
            else -> "%02d.%02d %s".format(t.dayOfMonth, t.monthValue, hm)
        }
    }
}
