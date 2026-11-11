package com.phoenix.app

/** Изменения в источнике между двумя скачиваниями: резко стал меньше, резко вырос или почти целиком заменён. */
object SourceDrift {
    enum class Kind { SHRUNK, GREW, REPLACED }

    class Drift(val kind: Kind, val text: String)

    const val MIN_SIZE = 20

    fun check(prev: Set<String>?, now: Set<String>): Drift? {
        if (prev == null || prev.size < MIN_SIZE) return null
        val kept = prev.intersect(now).size
        return when {
            now.size * 10 <= prev.size * 4 -> Drift(Kind.SHRUNK, "конфигов стало ${now.size} вместо ${prev.size}")
            now.size >= prev.size * 3 -> Drift(Kind.GREW, "конфигов стало ${now.size} вместо ${prev.size}")
            now.size >= MIN_SIZE && kept * 5 < prev.size -> Drift(Kind.REPLACED, "заменено ${100 - kept * 100 / prev.size}% содержимого (осталось $kept из ${prev.size})")
            else -> null
        }
    }
}

/**
 * Разнообразие типов в начале очереди: если лучшие конфиги подряд одного типа (например, все Reality), а оператор режет именно его,
 * поиск тратит время на заведомо похожие. Первые [keep] остаются как есть, в следующих [window] ни один тип не занимает больше [maxShare].
 */
object ProtocolQuota {
    fun diversify(list: List<Candidate>, keep: Int = 4, window: Int = 24, maxShare: Double = 0.6): List<Candidate> {
        if (list.size <= keep + 1) return list
        val out = ArrayList<Candidate>(list.size)
        val rest = ArrayList(list)
        val counts = HashMap<String, Int>()
        fun take(c: Candidate) {
            out += c
            rest.remove(c)
            ProtocolLearning.kindOf(c.link).let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        repeat(minOf(keep, rest.size)) { take(rest.first()) }
        while (out.size < window && rest.isNotEmpty()) {
            val limit = kotlin.math.ceil(maxShare * (out.size + 1)).toInt().coerceAtLeast(1)
            val pick = rest.firstOrNull { (counts[ProtocolLearning.kindOf(it.link)] ?: 0) < limit } ?: rest.first()
            take(pick)
        }
        out += rest
        return out
    }
}
