package com.phoenix.app

import java.io.File

/**
 * Частые смены сети (Wi-Fi ↔ мобильная, журнал 06.11: 32 смены и 24 смены режима за 11 часов).
 * Если сеть меняется ≥ [LIMIT] раз за [WINDOW_MS], одного определения типа блокировок мало: режим подтверждается дольше,
 * иначе каждая смена запускает дорогое переключение режима и перебор серверов.
 */
class FlapGuard(private val windowMs: Long = WINDOW_MS, private val limit: Int = LIMIT) {
    private val times = ArrayList<Long>()

    @Synchronized fun note(now: Long) {
        trim(now)
        times.add(now)
    }

    @Synchronized fun count(now: Long): Int {
        trim(now)
        return times.size
    }

    fun flapping(now: Long): Boolean = count(now) >= limit

    private fun trim(now: Long) {
        times.removeAll { now - it > windowMs || now < it }
    }

    companion object {
        const val WINDOW_MS = 180_000L
        const val LIMIT = 2
    }
}

/** Последний сервер, который реально работал в сети [net] в режиме [mode]; при смене режима пробуется первым. */
class LastGoodStore(private val file: File) {
    private data class Row(val net: String, val mode: String, val link: String, val at: Long)

    private fun load(): MutableList<Row> {
        if (!file.exists()) return ArrayList()
        val out = ArrayList<Row>()
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size < 4) continue
            val at = p[3].toLongOrNull() ?: continue
            out += Row(p[0], p[1], p[2], at)
        }
        return out
    }

    @Synchronized fun remember(net: String, mode: String, link: String, now: Long) {
        if (net.isBlank() || link.isBlank() || net.contains('\t') || link.contains('\t')) return
        val rows = load()
        rows.removeAll { it.net == net && it.mode == mode }
        rows += Row(net, mode, link, now)
        val keep = rows.sortedByDescending { it.at }.take(MAX_ROWS)
        file.writeText(keep.joinToString("\n") { "${it.net}\t${it.mode}\t${it.link}\t${it.at}" } + "\n")
    }

    @Synchronized fun get(net: String, mode: String, now: Long, maxAgeMs: Long = MAX_AGE_MS): String? =
        load().firstOrNull { it.net == net && it.mode == mode && now - it.at in 0..maxAgeMs }?.link

    companion object {
        const val MAX_ROWS = 64
        const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
        private val cache = java.util.concurrent.ConcurrentHashMap<String, LastGoodStore>()
        fun of(file: File): LastGoodStore = cache.getOrPut(file.absolutePath) { LastGoodStore(file) }
    }
}

/** Сколько времени за день не было связи из-за смены режима и смены сервера (для оценки, где теряются минуты). */
class DowntimeStats(private val file: File) {
    private data class Row(val day: String, val kind: String, val count: Int, val total: Int, val max: Int)

    private fun load(): MutableList<Row> {
        if (!file.exists()) return ArrayList()
        val out = ArrayList<Row>()
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size < 5) continue
            out += Row(p[0], p[1], p[2].toIntOrNull() ?: continue, p[3].toIntOrNull() ?: continue, p[4].toIntOrNull() ?: continue)
        }
        return out
    }

    /** @return false, если перерыв слишком длинный, чтобы его считать (скорее всего, интернета не было вообще) */
    @Synchronized fun add(kind: String, sec: Int, day: String): Boolean {
        if (sec < 0 || sec > MAX_SEC) return false
        val rows = load()
        val i = rows.indexOfFirst { it.day == day && it.kind == kind }
        if (i >= 0) {
            val r = rows[i]
            rows[i] = r.copy(count = r.count + 1, total = r.total + sec, max = maxOf(r.max, sec))
        } else rows += Row(day, kind, 1, sec, sec)
        val days = rows.map { it.day }.distinct().sortedDescending().take(KEEP_DAYS).toSet()
        val keep = rows.filter { it.day in days }
        file.writeText(keep.joinToString("\n") { "${it.day}\t${it.kind}\t${it.count}\t${it.total}\t${it.max}" } + "\n")
        return true
    }

    @Synchronized fun summary(day: String): String {
        val rows = load().filter { it.day == day }
        if (rows.isEmpty()) return "перерывов нет"
        val n = rows.sumOf { it.count }
        val total = rows.sumOf { it.total }
        val max = rows.maxOf { it.max }
        val parts = rows.joinToString(", ") { "${kindTitle(it.kind)} ${it.count}" }
        return "перерывов $n, всего ${total / 60} мин ${total % 60} с, самый долгий $max с ($parts)"
    }

    companion object {
        const val MAX_SEC = 1_800
        const val KEEP_DAYS = 14
        const val MODE = "MODE"
        const val FAILOVER = "FAILOVER"
        fun kindTitle(kind: String) = when (kind) { MODE -> "смена режима"; FAILOVER -> "смена сервера"; else -> kind }
        private val cache = java.util.concurrent.ConcurrentHashMap<String, DowntimeStats>()
        fun of(file: File): DowntimeStats = cache.getOrPut(file.absolutePath) { DowntimeStats(file) }
    }
}

/**
 * Общая отметка «сеть сейчас часто меняется»: пока она стоит, тяжёлые фоновые задачи (полная перепроверка, проверка целей) откладываются,
 * чтобы не гонять проверки по сети, которая через минуту станет другой (журнал 06.11: 24 смены режима за 11 часов).
 */
object NetFlap {
    const val DEFER_MS = 120_000L
    @Volatile private var until = 0L
    fun mark(now: Long) { until = now + DEFER_MS }
    fun busy(now: Long): Boolean = now < until && until - now <= DEFER_MS
    fun clear() { until = 0L }
}

/**
 * Не повторять в журнале одинаковую строку чаще, чем раз в [quietMs]: в журнале 07.11 одна и та же строка DETECT («чёрные списки · 10 из 11…»)
 * повторялась 1322 раза и занимала 13 % файла, из-за чего пять мегабайт хватало меньше чем на сутки.
 */
class RepeatMute(private val quietMs: Long = 30 * 60_000L) {
    private data class Last(val text: String, val at: Long)
    private val last = HashMap<String, Last>()

    @Synchronized fun shouldLog(key: String, text: String, now: Long): Boolean {
        val l = last[key]
        if (l != null && l.text == text && now >= l.at && now - l.at < quietMs) return false
        last[key] = Last(text, now)
        return true
    }
}

/**
 * Подмешивает «чужие» серверы (белых списков при поиске в чёрных) в очередь, а не оставляет их в самом конце.
 * Журнал 07.11: в чёрных списках очередь из ~630 своих серверов никогда не доходила до белых, а Gemini и NotebookLM закрыты на «обычных» выходах.
 * После каждых [every] своих идёт один чужой, пока чужие не кончатся.
 */
object QueueMix {
    fun <T> mix(own: List<T>, foreign: List<T>, every: Int = 2): List<T> {
        if (foreign.isEmpty()) return own
        val out = ArrayList<T>(own.size + foreign.size)
        var f = 0
        var sinceF = 0
        for (o in own) {
            if (sinceF >= every && f < foreign.size) { out += foreign[f++]; sinceF = 0 }
            out += o
            sinceF++
        }
        while (f < foreign.size) out += foreign[f++]
        return out
    }
}

/**
 * Первый запуск без истории (нет бэкапа, ни одного успешного сбора): всё надо проверять с нуля, десятки тысяч
 * конфигов. Чтобы телефон не захлёбывался (приложение «не отвечает»), первый сбор откладывается на минуту-полторы,
 * а первая перепроверка идёт втрое меньшим числом параллельных проверок.
 */
object ColdStart {
    const val FIRST_DELAY_SEC = 90L

    fun isFirstRun(lastSuccessAt: Long): Boolean = lastSuccessAt <= 0L

    /** Сколько проверок через ядро одновременно при перепроверке; [base] — обычное число для телефона. */
    fun recheckParallel(base: Int, lastSuccessAt: Long): Int =
        if (isFirstRun(lastSuccessAt)) (base / 3).coerceAtLeast(2) else base

    /** Задержка запуска «догона» при старте процесса; ручной запуск и будильник не откладываются. */
    fun catchUpDelaySec(trigger: String, lastSuccessAt: Long): Long =
        if (trigger == "catch-up" && isFirstRun(lastSuccessAt)) FIRST_DELAY_SEC else 0L
}
