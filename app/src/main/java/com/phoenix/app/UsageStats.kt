package com.phoenix.app

import java.io.File
import java.time.LocalDate

/**
 * Статистика работы по дням: сколько времени VPN был включён, сколько раз менялся сервер (и почему), на каких серверах держался дольше всего.
 * Только на телефоне; хранится 30 дней; на диск пишется не чаще раза в минуту и при отключении.
 */
class UsageStats(private val file: File) {
    private val days = LinkedHashMap<String, LinkedHashMap<String, Long>>()
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L

    companion object {
        const val KEEP_DAYS = 30
        const val SAVE_EVERY_MS = 60_000L
        const val MAX_SERVERS_PER_DAY = 25
        const val SERVER_PREFIX = "s:"

        const val CONNECTED = "conn"
        const val SESSIONS = "sess"
        const val SWITCHES = "switch"
        const val SWITCH_APP = "sw_app"
        const val SWITCH_SMART = "sw_smart"
        const val FAILURES = "fail"
        const val NO_SERVER = "nosrv"
        const val VETOES = "veto"

        private val cache = java.util.concurrent.ConcurrentHashMap<String, UsageStats>()
        fun of(file: File): UsageStats = cache.getOrPut(file.absolutePath) { UsageStats(file) }

        fun serverKey(title: String): String = SERVER_PREFIX + title.replace('\t', ' ').replace('\n', ' ').trim().take(80)
    }

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            file.forEachLine { l ->
                val p = l.split('\t')
                val v = p.getOrNull(2)?.toLongOrNull()
                if (p.size >= 3 && v != null) days.getOrPut(p[0]) { LinkedHashMap() }[p[1]] = v
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(key: String, delta: Long, day: String, now: Long) {
        if (delta <= 0) return
        load()
        val d = days.getOrPut(day) { LinkedHashMap() }
        d[key] = (d[key] ?: 0L) + delta
        dirty = true
        if (now - lastSave >= SAVE_EVERY_MS) save(now)
    }

    /** Время работы: [seconds] секунд общих и на сервере [title]. */
    fun addConnected(title: String, seconds: Long, day: String, now: Long) {
        add(CONNECTED, seconds, day, now)
        if (title.isNotBlank()) add(serverKey(title), seconds, day, now)
    }

    @Synchronized
    fun save(now: Long) {
        load()
        if (!dirty) return
        val keepFrom = try { LocalDate.parse(days.keys.maxOrNull() ?: return).minusDays(KEEP_DAYS.toLong() - 1).toString() } catch (_: Exception) { "" }
        days.keys.filter { it < keepFrom }.forEach { days.remove(it) }
        for ((_, d) in days) {
            val servers = d.keys.filter { it.startsWith(SERVER_PREFIX) }
            if (servers.size > MAX_SERVERS_PER_DAY) servers.sortedBy { d[it] ?: 0L }.take(servers.size - MAX_SERVERS_PER_DAY).forEach { d.remove(it) }
        }
        try {
            file.parentFile?.mkdirs()
            file.writeTextAtomic(days.entries.joinToString("") { (day, m) -> m.entries.joinToString("") { "$day\t${it.key}\t${it.value}\n" } })
            dirty = false
            lastSave = now
        } catch (_: Exception) {
        }
    }

    class Day(val day: String, val values: Map<String, Long>) {
        fun get(key: String) = values[key] ?: 0L
    }

    /** Последние [count] дней, сегодняшний первым (дни без записей тоже есть, пустые). */
    @Synchronized
    fun lastDays(today: LocalDate, count: Int): List<Day> {
        load()
        return (0 until count).map { i ->
            val d = today.minusDays(i.toLong()).toString()
            Day(d, days[d]?.toMap() ?: emptyMap())
        }
    }
}

object StatsText {
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "$s с"
            s < 3600 -> "${s / 60} мин"
            else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        }
    }

    /** Строки для экрана: сводка по дням, итог за неделю, лучшие серверы. */
    fun lines(days: List<UsageStats.Day>, topServers: Int = 3): List<String> {
        if (days.all { it.values.isEmpty() }) return listOf("Пока нет данных: статистика появится после первого подключения.")
        val out = ArrayList<String>()
        for ((i, d) in days.withIndex()) {
            if (d.values.isEmpty()) continue
            val label = when (i) { 0 -> "Сегодня"; 1 -> "Вчера"; else -> d.day.takeLast(5).replace('-', '.').split('.').reversed().joinToString(".") }
            out += "$label: VPN ${duration(d.get(UsageStats.CONNECTED))}, подключений ${d.get(UsageStats.SESSIONS)}, смен сервера ${d.get(UsageStats.SWITCHES)}" +
                (if (d.get(UsageStats.FAILURES) > 0) ", обрывов ${d.get(UsageStats.FAILURES)}" else "")
        }
        val total = { k: String -> days.sumOf { it.get(k) } }
        out += "За ${days.size} дн.: VPN ${duration(total(UsageStats.CONNECTED))}, смен ${total(UsageStats.SWITCHES)} " +
            "(под открытое приложение ${total(UsageStats.SWITCH_APP)}, «умная» ${total(UsageStats.SWITCH_SMART)}), обрывов ${total(UsageStats.FAILURES)}, " +
            "раз без запасного сервера ${total(UsageStats.NO_SERVER)}, ваших отметок «не открывается» ${total(UsageStats.VETOES)}"
        val perServer = HashMap<String, Long>()
        for (d in days) for ((k, v) in d.values) if (k.startsWith(UsageStats.SERVER_PREFIX)) perServer[k.removePrefix(UsageStats.SERVER_PREFIX)] = (perServer[k.removePrefix(UsageStats.SERVER_PREFIX)] ?: 0L) + v
        val best = perServer.entries.sortedByDescending { it.value }.take(topServers)
        if (best.isNotEmpty()) {
            out += "Дольше всего держались:"
            best.forEach { out += "  ${it.key} — ${duration(it.value)}" }
        }
        return out
    }
}

/** Баннер «система закрыла Phoenix в фоне»: показывается сутки после такого закрытия, пока не скрыт. */
object KillBanner {
    const val SHOW_MS = 24 * 60 * 60_000L

    /** Причины, при которых система сама остановила приложение (не пользователь и не обновление). */
    fun systemKill(code: Int): Boolean = code in setOf(2, 3, 9, 13, 14)

    fun text(code: Int, ageMs: Long, hint: String): String {
        val ago = if (ageMs < 3_600_000L) "${(ageMs / 60_000L).coerceAtLeast(1)} мин назад" else "${ageMs / 3_600_000L} ч назад"
        return "Система закрыла Phoenix в фоне ($ago): ${ExitReasons.reason(code)}. Пока приложение было закрыто, VPN мог не работать. $hint"
    }

    fun visible(killTs: Long, ackTs: Long, now: Long): Boolean = killTs > 0 && killTs > ackTs && now >= killTs && now - killTs <= SHOW_MS
}
