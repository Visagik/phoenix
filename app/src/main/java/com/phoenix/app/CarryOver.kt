package com.phoenix.app

import java.time.LocalDate

/**
 * Перенос рабочих конфигов прошлого сбора в новый. Без него неудачный сбор (скачались 2 источника из 22)
 * целиком заменял пул, и при смене сервера оставалось перебирать ~20 конфигов вместо ~1400.
 *
 * Конфиг переносится, пока он не «умер» по проверкам и пока не прошло [days] дней с тех пор, как он последний раз
 * приходил из источника. Появился в источнике снова — срок отсчитывается заново. 0 дней — перенос выключен.
 */
object CarryOver {
    /** Сколько хранить отметки «последний раз видел в источнике», даже если перенос выключен или срок большой. */
    private const val KEEP_DAYS = 30L

    fun decode(text: String): Map<String, LocalDate> {
        val out = LinkedHashMap<String, LocalDate>()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size < 2) continue
            val d = try { LocalDate.parse(p[1].trim()) } catch (_: Exception) { continue }
            out[p[0].trim()] = d
        }
        return out
    }

    fun encode(seen: Map<String, LocalDate>): String =
        seen.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n"

    /**
     * @param prevLines строки прошлого пула («ссылка#имя»); @param newKeys ключи конфигов, пришедших из источников сейчас
     * @param alive false — конфиг проверкой признан мёртвым (не известный проверкам считается живым)
     */
    fun select(
        prevLines: List<String>,
        newKeys: Set<String>,
        seen: Map<String, LocalDate>,
        today: LocalDate,
        days: Int,
        keyOf: (String) -> String,
        alive: (String) -> Boolean,
    ): List<String> {
        if (days <= 0) return emptyList()
        val taken = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in prevLines) {
            val line = raw.trim()
            if (!line.contains("://") || line.startsWith("#")) continue
            val key = keyOf(line)
            if (key in newKeys || !taken.add(key)) continue
            // нет отметки (первый запуск после обновления) — считаем, что видели сегодня: срок пойдёт с этого сбора
            val last = seen[key] ?: today
            if (last.plusDays(days.toLong()).isBefore(today)) continue
            if (!alive(key)) continue
            out += line
        }
        return out
    }

    /** Обновляет отметки: пришедшие сейчас — сегодня; старше [KEEP_DAYS] удаляются. Перенесённые не обновляются. */
    fun touch(seen: Map<String, LocalDate>, newKeys: Set<String>, carriedKeys: Set<String>, today: LocalDate): Map<String, LocalDate> {
        val out = LinkedHashMap<String, LocalDate>()
        for ((k, d) in seen) if (!d.plusDays(KEEP_DAYS).isBefore(today)) out[k] = d
        for (k in newKeys) out[k] = today
        for (k in carriedKeys) if (k !in out) out[k] = today
        return out
    }
}
