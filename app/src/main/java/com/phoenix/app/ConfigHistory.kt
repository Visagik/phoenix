package com.phoenix.app

import java.io.File

/**
 * Накопленная статистика одного конфига в одной сети (например, «Wi-Fi» или «МТС»):
 * сколько раз он работал и сколько нет. Старые результаты постепенно «забываются».
 */
data class HistStat(val ok: Int, val fail: Int, val lastAt: Long, val mbps: Double = -1.0) {
    val total: Int get() = ok + fail

    /** Доля удач со сглаживанием: нет данных → 0.5, одна удача → 0.67, пять неудач подряд → 0.14. */
    val reliability: Double get() = (ok + 1.0) / (total + 2.0)

    /** Достаточно данных и конфиг в этой сети почти не работает. */
    val bad: Boolean get() = total >= MIN_FOR_BAD && reliability < BAD_BELOW

    fun after(success: Boolean, now: Long, speed: Double = -1.0): HistStat {
        var o = ok
        var f = fail
        if (success) o++ else f++
        if (o + f > MAX_TOTAL) {
            o = (o + 1) / 2
            f = (f + 1) / 2
        }
        val m = when {
            speed < 0 -> mbps
            mbps < 0 -> speed
            else -> mbps * 0.7 + speed * 0.3
        }
        return HistStat(o, f, now, m)
    }

    companion object {
        const val MAX_TOTAL = 24
        const val MIN_FOR_BAD = 3
        const val BAD_BELOW = 0.35
    }
}

/** Одно наблюдение: конфиг [identity] в сети [ctx] сработал или нет (и с какой скоростью, если мерили). */
data class HistEvent(val identity: String, val ctx: String, val success: Boolean, val mbps: Double = -1.0)

/**
 * Лёгкий фоновый «опыт» приложения. Ничего не проверяет само — только записывает то, что уже известно из проверок,
 * переключений и замеров. По нему ранжирование ставит выше конфиги, которые именно в этой сети работали,
 * и ниже те, что в ней подводили. Файл: identity, сеть, удач, неудач, время, скорость (через табуляцию).
 */
class ConfigHistory private constructor(private val file: File, private val maxLines: Int = MAX_LINES, private val daypart: Boolean = true) {
    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ConfigHistory>()

        operator fun invoke(file: File, maxLines: Int = MAX_LINES, daypart: Boolean = true): ConfigHistory =
            cache.getOrPut(file.absolutePath) { ConfigHistory(file, maxLines, daypart) }

        /** Опыт «сеть + время суток» хранится под ключом «сеть|часть дня», обычный опыт сети — отдельно. */
        const val PART_SEP = '|'

        /** Записи, которые не обновлялись столько дней, забываются. */
        const val KEEP_DAYS = 60L
        const val MAX_LINES = 6000

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        fun keyOf(identity: String, ctx: String) = clean(identity) + "\t" + clean(ctx)
    }

    /** Ключ «identity\tсеть» → статистика. */
    @Synchronized
    fun load(): Map<String, HistStat> {
        if (!file.exists()) return emptyMap()
        val map = LinkedHashMap<String, HistStat>()
        try {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 5) return@forEachLine
                val ok = p[2].toIntOrNull() ?: return@forEachLine
                val fail = p[3].toIntOrNull() ?: return@forEachLine
                val at = p[4].toLongOrNull() ?: return@forEachLine
                map[p[0] + "\t" + p[1]] = HistStat(ok, fail, at, p.getOrNull(5)?.toDoubleOrNull() ?: -1.0)
            }
        } catch (_: Exception) {
        }
        return map
    }

    @Synchronized
    fun record(events: List<HistEvent>, now: Long = System.currentTimeMillis()) {
        if (events.isEmpty()) return
        val map = LinkedHashMap(load())
        for (e in events) {
            if (e.ctx == "none") continue // сети нет (смена сети): опыт «там» никому не нужен и портит статистику
            val k = keyOf(e.identity, e.ctx)
            map[k] = (map[k] ?: HistStat(0, 0, now)).after(e.success, now, e.mbps)
            if (daypart && PART_SEP !in e.ctx) {
                val kp = keyOf(e.identity, e.ctx + PART_SEP + DayPart.of(now))
                map[kp] = (map[kp] ?: HistStat(0, 0, now)).after(e.success, now, e.mbps)
            }
        }
        write(map, now)
    }

    fun record(identity: String, ctx: String, success: Boolean, mbps: Double = -1.0, now: Long = System.currentTimeMillis()) =
        record(listOf(HistEvent(identity, ctx, success, mbps)), now)

    private fun write(map: Map<String, HistStat>, now: Long) {
        val limit = now - KEEP_DAYS * 86_400_000L
        val kept = map.entries.filter { it.value.lastAt >= limit }.sortedByDescending { it.value.lastAt }.take(maxLines)
        file.parentFile?.mkdirs()
        file.writeTextAtomic(kept.joinToString("\n") { (k, s) -> "$k\t${s.ok}\t${s.fail}\t${s.lastAt}\t${s.mbps}" } + if (kept.isEmpty()) "" else "\n")
    }

    /** Функция «identity → статистика в сети [ctx]» для ранжирования. */
    fun view(ctx: String, now: Long = System.currentTimeMillis()): (String) -> HistStat? {
        val all = load()
        val part = ctx + PART_SEP + DayPart.of(now)
        // в это время суток данных достаточно — берём их (вечером сеть бывает хуже, чем утром), иначе общий опыт сети
        return { identity ->
            val p = if (daypart) all[keyOf(identity, part)] else null
            if (p != null && p.total >= DayPart.MIN_FOR_PART) p else all[keyOf(identity, ctx)]
        }
    }

    /** Для всех конфигов сразу: личность → (сеть, статистика), самые весомые первыми. */
    fun placesByIdentity(): Map<String, List<Pair<String, HistStat>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, HistStat>>>()
        for ((key, stat) in load()) {
            val tab = key.indexOf('\t')
            if (tab < 0) continue
            if (key.substring(tab + 1) == "none" || PART_SEP in key.substring(tab + 1)) continue
            out.getOrPut(key.substring(0, tab)) { ArrayList() } += key.substring(tab + 1) to stat
        }
        return out.mapValues { (_, v) -> v.sortedByDescending { it.second.total } }
    }

    /** Где этот конфиг работал и где нет: пары (сеть, статистика), самые весомые первыми. */
    fun placesFor(identity: String): List<Pair<String, HistStat>> {
        val prefix = clean(identity) + "\t"
        return load().entries.filter { it.key.startsWith(prefix) }
            .map { it.key.substring(prefix.length) to it.value }
            .filter { it.first != "none" && PART_SEP !in it.first }
            .sortedByDescending { it.second.total }
    }
}

/** Часть суток по местному времени: ночь 0–6, утро 6–12, день 12–18, вечер 18–24. */
object DayPart {
    const val MIN_FOR_PART = 3

    fun of(hour: Int): String = when (hour) {
        in 0..5 -> "ночь"
        in 6..11 -> "утро"
        in 12..17 -> "день"
        else -> "вечер"
    }

    fun of(nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        of(java.time.Instant.ofEpochMilli(nowMs).atZone(zone).hour)
}

/** Названия сетей для истории. */
object NetLabel {
    /** [kind] — имя значения NetInfo.Kind: WIFI, ETHERNET, MOBILE, NONE. */
    fun of(kind: String, operator: String?): String = when (kind) {
        "WIFI" -> "wifi"
        "ETHERNET" -> "cable"
        "MOBILE" -> "mobile:" + (operator?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty().ifBlank { "?" })
        else -> "none"
    }

    fun title(ctx: String): String = when {
        ctx == "wifi" -> "Wi-Fi"
        ctx == "cable" -> "кабель"
        ctx.startsWith("mobile:") -> ctx.removePrefix("mobile:").let { if (it == "?") "моб. сеть" else "моб. $it" }
        else -> ctx
    }

    /** «Wi-Fi 12✓/1✗ · моб. мтс 0✓/5✗» */
    fun summary(places: List<Pair<String, HistStat>>, max: Int = 3): String =
        places.take(max).joinToString(" · ") { (ctx, s) -> "${title(ctx)} ${s.ok}✓/${s.fail}✗" }
}
