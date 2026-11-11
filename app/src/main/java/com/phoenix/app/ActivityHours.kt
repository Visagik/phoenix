package com.phoenix.app

import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Часы активности: в какие часовые слоты недели телефоном обычно пользуются. Слот = день недели × час (168 слотов).
 * Для каждого слота хранится, в скольких разных днях он был «живым» (экран включался), не больше [CAP].
 * Данные только на телефоне, один маленький файл (строка «слот TAB дней TAB последний день»), входит в резервную копию.
 */
class ActivityHours(private val file: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    private data class Cell(val days: Int, val lastDay: Long)

    private var cells: MutableMap<Int, Cell>? = null
    private var firstDay = 0L

    @Synchronized
    private fun ensure(): MutableMap<Int, Cell> {
        cells?.let { return it }
        val m = HashMap<Int, Cell>()
        try {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 2 && p[0] == "first") { firstDay = p[1].toLongOrNull() ?: 0L; return@forEachLine }
                if (p.size < 3) return@forEachLine
                val s = p[0].toIntOrNull() ?: return@forEachLine
                m[s] = Cell(p[1].toIntOrNull() ?: return@forEachLine, p[2].toLongOrNull() ?: return@forEachLine)
            }
        } catch (_: Exception) {
        }
        cells = m
        return m
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val m = cells ?: return
            file.writeTextAtomic("first\t$firstDay\n" + m.entries.sortedBy { it.key }.joinToString("\n") { (s, c) -> "$s\t${c.days}\t${c.lastDay}" } + "\n")
        } catch (_: Exception) {
        }
    }

    /** Отметить: сейчас телефоном пользуются. Один день в слоте считается один раз. */
    @Synchronized
    fun recordActive(now: Long = System.currentTimeMillis()) {
        val m = ensure()
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val day = t.toLocalDate().toEpochDay()
        val slot = slotOf(t.dayOfWeek.value - 1, t.hour)
        if (firstDay == 0L) firstDay = day
        val c = m[slot]
        if (c != null && c.lastDay == day) return
        val old = if (c == null || day - c.lastDay > FORGET_DAYS) 0 else c.days
        m[slot] = Cell(minOf(CAP, old + 1), day)
        save()
    }

    /** Активность слота относительно самого живого: 0..1. */
    @Synchronized
    fun level(slot: Int, now: Long = System.currentTimeMillis()): Double {
        val m = ensure()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        fun live(c: Cell?) = if (c == null || today - c.lastDay > FORGET_DAYS) 0 else c.days
        val max = m.values.maxOfOrNull { live(it) } ?: 0
        if (max == 0) return 0.0
        return live(m[((slot % WEEK) + WEEK) % WEEK]).toDouble() / max
    }

    /** Достаточно ли данных, чтобы им верить: наблюдаем не меньше недели. */
    @Synchronized
    fun confident(now: Long = System.currentTimeMillis()): Boolean {
        ensure()
        if (firstDay == 0L) return false
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        return today - firstDay >= MIN_OBSERVED_DAYS
    }

    fun slotNow(now: Long = System.currentTimeMillis()): Int {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return slotOf(t.dayOfWeek.value - 1, t.hour)
    }

    /** Сейчас «тихий» слот: данным можно верить, а активности почти нет. */
    fun isQuietNow(now: Long = System.currentTimeMillis()): Boolean =
        confident(now) && level(slotNow(now), now) < QUIET_BELOW

    /** Скоро начнётся оживлённый период: сейчас слот спокойный, а следующий час обычно активный (за [leadMin] минут до него). */
    fun peakSoon(now: Long = System.currentTimeMillis(), leadMin: Int = 15): Boolean {
        if (!confident(now)) return false
        val t = Instant.ofEpochMilli(now).atZone(zone)
        if (60 - t.minute > leadMin) return false
        val cur = slotNow(now)
        return level(cur, now) < PEAK_FROM_LOW && level(cur + 1, now) >= PEAK_ABOVE
    }

    companion object {
        const val WEEK = 168
        const val CAP = 12
        const val FORGET_DAYS = 56L
        const val MIN_OBSERVED_DAYS = 7L
        const val QUIET_BELOW = 0.15
        const val PEAK_ABOVE = 0.5
        const val PEAK_FROM_LOW = 0.3

        fun slotOf(dayOfWeekMon0: Int, hour: Int) = dayOfWeekMon0 * 24 + hour
    }
}

/** Ручные тихие часы: интервал «с часа — по час» (через полночь тоже), сильнее автоматических. */
object QuietWindow {
    fun inside(hour: Int, from: Int, to: Int): Boolean = when {
        from == to -> false
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }
}
