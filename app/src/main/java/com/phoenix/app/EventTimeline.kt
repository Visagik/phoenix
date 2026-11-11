package com.phoenix.app

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Лента «Что произошло?»: из строк журнала за последние минуты оставляет главное и пишет коротко. Ничего нового не собирает. */
object EventTimeline {
    private val TAGS = listOf("VPN:", "FAILOVER:", "HEALTH:", "NET:", "SEARCH:", "SWITCH:", "SLOW:", "MODE:", "RECHECK:")
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    data class Item(val time: String, val text: String)

    fun build(lines: List<String>, now: OffsetDateTime, zone: ZoneId, windowMin: Long = 15, maxItems: Int = 14): List<Item> {
        val from = now.minusMinutes(windowMin)
        val out = ArrayList<Item>()
        var last = ""
        for (raw in lines) {
            val sp = raw.indexOf(' ')
            if (sp <= 0) continue
            val ts = try { OffsetDateTime.parse(raw.substring(0, sp)) } catch (_: Exception) { continue }
            if (ts.isBefore(from) || ts.isAfter(now.plusMinutes(1))) continue
            val msg = raw.substring(sp + 1).trim()
            if (TAGS.none { msg.startsWith(it) }) continue
            val text = shorten(msg)
            if (text == last) continue
            last = text
            out += Item(ts.atZoneSameInstant(zone).format(clock), text)
        }
        return out.takeLast(maxItems)
    }

    /** Убирает служебные хвосты в скобках и длинные списки, чтобы строка помещалась на экране. */
    fun shorten(msg: String, max: Int = 130): String {
        val s = msg.replace(Regex("\\s+"), " ")
        return if (s.length <= max) s else s.take(max - 1) + "…"
    }
}
