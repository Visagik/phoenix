package com.phoenix.app

import java.io.File

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

    private fun load(from: File = file): MutableList<Row> {
        if (!from.exists()) return ArrayList()
        val out = ArrayList<Row>()
        val lines = try { from.readLines() } catch (_: Exception) { return ArrayList() }
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 5) continue
            out += Row(p[0], p[1], p[2].toIntOrNull() ?: continue, p[3].toIntOrNull() ?: continue, p[4].toIntOrNull() ?: continue)
        }
        return out
    }

    private fun write(rows: List<Row>) {
        val days = rows.map { it.day }.distinct().sortedDescending().take(KEEP_DAYS).toSet()
        val keep = rows.filter { it.day in days }
        file.writeText(keep.joinToString("\n") { "${it.day}\t${it.kind}\t${it.count}\t${it.total}\t${it.max}" } + "\n")
    }

    /**
     * У5: сливает в этот файл прежние файлы режимов (у каждого режима был свой): за один день и один вид перерывы и секунды
     * складываются, «самый долгий» — максимум. Битые строки пропускаются. Слитые файлы удаляются (повторный запуск ничего не меняет).
     */
    @Synchronized fun absorb(others: List<File>) {
        val old = others.filter { it.exists() && it.absoluteFile != file.absoluteFile }
        if (old.isEmpty()) return
        val rows = load()
        for (f in old) for (r in load(f)) {
            val i = rows.indexOfFirst { it.day == r.day && it.kind == r.kind }
            if (i >= 0) { val c = rows[i]; rows[i] = c.copy(count = c.count + r.count, total = c.total + r.total, max = maxOf(c.max, r.max)) } else rows += r
        }
        write(rows)
        for (f in old) try { f.delete() } catch (_: Exception) { }
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
        write(rows)
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
        /** Связь пропала сама (поиск не нашёл другой сервер), потом вернулась на прежнем. */
        const val LOSS = "LOSS"
        /** Перерыв прежней сети закрыт сменой сети (Wi-Fi ↔ мобильная). */
        const val NET = "NET"
        fun kindTitle(kind: String) = when (kind) { MODE -> "смена режима"; FAILOVER -> "смена сервера"; LOSS -> "сбой связи"; NET -> "смена сети"; else -> kind }
        private val cache = java.util.concurrent.ConcurrentHashMap<String, DowntimeStats>()
        fun of(file: File): DowntimeStats = cache.getOrPut(file.absolutePath) { DowntimeStats(file) }

        /** Общий счётчик перерывов (один на оба режима): при первом обращении сливает прежние файлы режимов [legacy]. */
        fun shared(file: File, legacy: List<File>): DowntimeStats = of(file).also { it.absorb(legacy) }
    }
}
