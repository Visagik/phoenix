package com.phoenix.app

import java.security.MessageDigest

/** Что известно о конфиге источника: живой, неживой или ещё не проверялся. */
enum class CfgStatus { ALIVE, DEAD, UNKNOWN }

/** Сводка по одному источнику: сколько в нём конфигов и какая доля из проверенных работает. */
data class SourceStat(val url: String, val total: Int, val alive: Int, val dead: Int) {
    val checked: Int get() = alive + dead

    /** Доля живых среди проверенных (0..100), -1 — ничего не проверялось. */
    val percent: Int get() = if (checked == 0) -1 else Math.round(alive * 100.0 / checked).toInt()

    fun text(): String = when {
        total == 0 -> "конфигов нет"
        checked == 0 -> "$total конфигов · не проверялось"
        else -> "живых $percent% ($alive из $checked проверенных) · всего $total"
    }
}

/**
 * Какие конфиги пришли из какого источника, чтобы считать долю рабочих по каждой ссылке.
 * Конфиг опознаётся тем же коротким ключом, что и в файле проверок (хеш личности: протокол + имя).
 */
object SourceStats {
    /** Короткий ключ по личности конфига; совпадает с CheckStore.keyOf(ссылка). */
    fun idOfIdentity(identity: String): String =
        MessageDigest.getInstance("SHA-1").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    /** Строки «ссылка<TAB>ключ,ключ,…» (ссылки с табуляцией быть не может). */
    fun encode(members: Map<String, Set<String>>): String =
        members.entries.joinToString("\n") { (url, ids) -> url.replace('\t', ' ') + "\t" + ids.sorted().joinToString(",") } + "\n"

    fun decode(text: String): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for (line in text.lines()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            out[line.substring(0, tab)] = line.substring(tab + 1).split(',').filter { it.isNotBlank() }.toSet()
        }
        return out
    }

    /** По каждому источнику; сначала лучшие по доле рабочих, непроверенные в конце. */
    fun compute(members: Map<String, Set<String>>, statusOf: (String) -> CfgStatus): List<SourceStat> =
        members.map { (url, ids) ->
            var alive = 0
            var dead = 0
            for (id in ids) when (statusOf(id)) {
                CfgStatus.ALIVE -> alive++
                CfgStatus.DEAD -> dead++
                CfgStatus.UNKNOWN -> {}
            }
            SourceStat(url, ids.size, alive, dead)
        }.sortedWith(compareByDescending<SourceStat> { it.percent }.thenByDescending { it.alive }.thenBy { it.url })
}

/**
 * Приоритет ссылки-источника: насколько ей доверяет пользователь. Конфиги из «высокой» ссылки пробуются раньше
 * остальных (среди одинаково живых) и сама ссылка не попадает в чёрный список по аудиту; из «низкой» — позже.
 */
object SourcePriority {
    const val LOW = -1
    const val NORMAL = 0
    const val HIGH = 1

    fun title(level: Int): String = when {
        level > 0 -> "Высокий"
        level < 0 -> "Низкий"
        else -> "Обычный"
    }

    fun clamp(level: Int): Int = level.coerceIn(LOW, HIGH)

    /** Строки «ссылка<TAB>уровень»; обычный уровень не хранится. */
    fun encode(map: Map<String, Int>): String =
        map.filterValues { it != NORMAL }.entries.joinToString("") { (u, l) -> u.replace('\t', ' ') + "\t" + clamp(l) + "\n" }

    fun decode(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (line in text.lines()) {
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) continue
            val level = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            if (level != NORMAL) out[line.substring(0, tab)] = clamp(level)
        }
        return out
    }

    /**
     * Приоритет каждого конфига: самый высокий среди ссылок, где он встречается. Если конфиг есть в «обычной» ссылке,
     * «низкая» его не понижает (понижает только когда он встречается лишь в низких).
     */
    fun perConfig(members: Map<String, Set<String>>, priorities: Map<String, Int>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((url, ids) in members) {
            val level = priorities[url] ?: NORMAL
            for (id in ids) {
                val old = out[id]
                if (old == null || level > old) out[id] = level
            }
        }
        return out
    }
}

/** Файл приоритетов ссылок режима. */
class SourcePriorities(private val file: java.io.File) {
    fun load(): Map<String, Int> = try {
        if (file.exists()) SourcePriority.decode(file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun set(url: String, level: Int) {
        val map = LinkedHashMap(load())
        if (level == SourcePriority.NORMAL) map.remove(url) else map[url] = SourcePriority.clamp(level)
        file.parentFile?.mkdirs()
        file.writeTextAtomic(SourcePriority.encode(map))
    }
}

/**
 * Приоритеты источников, которые приложение ставит само по доле рабочих конфигов (по свежим проверкам):
 * лучшие источники пробуются первыми, у «мёртвых» в этой сети — последними. Ручной приоритет всегда сильнее.
 */
object AutoPriority {
    const val MIN_CHECKED_HIGH = 5
    const val MIN_CHECKED_LOW = 10

    /** Только источники с ненулевым уровнем. */
    fun levels(stats: List<SourceStat>, confirmLow: (String) -> Boolean = { true }): Map<String, Int> {
        val measured = stats.filter { it.checked >= MIN_CHECKED_HIGH }
        val best = measured.maxOfOrNull { it.percent } ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (s in stats) {
            when {
                // в белых списках живых мало (единицы процентов), поэтому «хороший» — относительно лучшего источника
                s.checked >= MIN_CHECKED_HIGH && s.alive >= 2 && best > 0 && s.percent >= best * 0.6 -> out[s.url] = SourcePriority.HIGH
                s.checked >= MIN_CHECKED_LOW && s.alive == 0 && confirmLow(s.url) -> out[s.url] = SourcePriority.LOW
            }
        }
        return out
    }

    /** Ручной уровень (если задан) сильнее автоматического. */
    fun effective(manual: Map<String, Int>, auto: Map<String, Int>): Map<String, Int> {
        val out = LinkedHashMap(auto)
        out.putAll(manual.filterValues { it != SourcePriority.NORMAL })
        return out
    }

    /** Пересчёт по свежим проверкам режима; возвращает число источников с авто-уровнем. */
    fun update(storage: Storage): Int {
        if (!storage.sourceMembersFile.exists()) return 0
        val members = SourceStats.decode(storage.sourceMembersFile.readText())
        val checks = CheckStore(storage.checksFile).load()
        val limit = java.time.LocalDate.now().minusDays(7).toString()
        val stats = SourceStats.compute(members) { id ->
            val c = checks[id]
            when {
                c != null && !c.dead && c.date >= limit -> CfgStatus.ALIVE
                c != null && c.dead && c.date >= limit -> CfgStatus.DEAD
                else -> CfgStatus.UNKNOWN
            }
        }
        // мягкое понижение: «низкий» только если источник выглядел мёртвым 3 разных дня подряд и его состав не обновлялся
        val cadence = SourceCadence(storage.sourceCadenceFile)
        val day = java.time.LocalDate.now().toEpochDay()
        for ((url, ids) in members) cadence.observe(url, ids, day)
        val deadLooking = stats.filter { it.checked >= MIN_CHECKED_LOW && it.alive == 0 }.map { it.url }.toSet()
        for (s in stats) if (s.url !in deadLooking) cadence.recover(s.url)
        val levels = levels(stats) { url -> cadence.strike(url, day) }
        cadence.forget(members.keys)
        cadence.save()
        storage.sourceAutoPriorityFile.parentFile?.mkdirs()
        try { UndoStore(storage.undoDir).snapshot("Авто-приоритеты источников", listOf(storage.sourceAutoPriorityFile), System.currentTimeMillis()) } catch (_: Throwable) {}
        storage.sourceAutoPriorityFile.writeTextAtomic(SourcePriority.encode(levels))
        val high = levels.count { it.value > 0 }
        storage.log("PRIO: авто-приоритеты источников: высокий $high, низкий ${levels.size - high} из ${stats.size} (лучший источник живых ${stats.maxOfOrNull { it.percent } ?: -1}%)")
        return levels.size
    }
}
