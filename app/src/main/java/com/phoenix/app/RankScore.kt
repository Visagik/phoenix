package com.phoenix.app

/**
 * Рейтинг сервера как сумма доводов (шаг 6 разбора, пункт 16). Действующий [Ranking.rank] — лексикографическая цепочка из 17 признаков:
 * задержка и скорость решают, только если равны все 15 предыдущих, а правило, вставленное в середину, молча меняет вес всех нижних.
 * Здесь те же признаки — слагаемые с весами, и новое правило — это один вес. Пока это ТЕНЬ: оценка считается рядом с рейтингом, ничего не
 * выбирает и только пишет в журнал, где она расходится с действующим порядком (и какие доводы это решили).
 */
object RankScore {
    data class Term(val name: String, val value: Double)

    /** Веса убывают примерно как порядок признаков в [Ranking.rank]: чем выше в цепочке, тем больше вес. */
    object W {
        const val BAD = 1000.0
        const val QUARANTINE = 500.0
        const val MISMATCH = 300.0
        const val UNFIT = 200.0
        const val ALIVE = 100.0
        const val RESERVE = 80.0
        const val PREFERRED = 60.0
        const val DEAD_MARK = 50.0
        const val MODE_HINT = 40.0
        const val PROTO = 20.0
        const val PRIO = 15.0
        const val STEADY = 10.0
        const val DAYS = 8.0
        const val CAPS = 6.0
        const val REL = 20.0
        const val MBPS = 5.0
        const val LATENCY = 3.0
    }

    /** Ненулевые слагаемые: по ним видно, почему сервер выше или ниже. */
    fun terms(c: Candidate): List<Term> = listOfNotNull(
        if (c.bad) Term("bad", -W.BAD) else null,
        if (c.quarantined) Term("карантин", -W.QUARANTINE) else null,
        if (c.mismatch) Term("чужой режим", -W.MISMATCH) else null,
        if (c.unfit) Term("не для общего", -W.UNFIT) else null,
        if (c.alive) Term("жив", W.ALIVE) else null,
        if (c.prio <= SourcePriority.RESERVE) Term("запасной", -W.RESERVE) else null,
        if (PoolPolicy.preferred(c)) Term("страна", W.PREFERRED) else null,
        if (c.deadMark) Term("отметка «мёртв»", -W.DEAD_MARK) else null,
        if (c.modeHint) Term("подсказка режима", W.MODE_HINT) else null,
        if (c.protoBucket != 1) Term("тип в сети", (1 - c.protoBucket) * W.PROTO) else null,
        if (c.prio > SourcePriority.RESERVE && c.prio != 0) Term("приоритет", c.prio * W.PRIO) else null,
        if (c.steady) Term("стабилен", W.STEADY) else null,
        if (c.days >= 3) Term("3+ дня", W.DAYS) else null,
        if (c.caps > 0) Term("сервисы", c.caps * W.CAPS) else null,
        if (Math.round(c.rel * 4) != 2L) Term("надёжность", (c.rel - 0.5) * W.REL) else null,
        if (c.mbps > 0) Term("скорость", minOf(c.mbps, 50.0) / 50.0 * W.MBPS) else null,
        Term("задержка", if (c.latencyMs <= 0) -W.LATENCY * 3 else -W.LATENCY * Math.log10(maxOf(c.latencyMs, 50L).toDouble())),
    )

    fun score(c: Candidate): Double = terms(c).sumOf { it.value }

    fun order(list: List<Candidate>): List<Candidate> = list.sortedByDescending { score(it) }

    data class Verdict(val agrees: Boolean, val actualTop: Candidate?, val scoreTop: Candidate?, val actualRankInScore: Int)

    /** Сравнивает первого в [actual] с лучшим по сумме доводов среди первых [limit]. */
    fun compare(actual: List<Candidate>, limit: Int = 200): Verdict {
        val a = actual.firstOrNull()
        val ranked = order(actual.take(limit))
        val s = ranked.firstOrNull()
        val rank = if (a == null) -1 else ranked.indexOfFirst { it.link == a.link } + 1
        return Verdict(a != null && s != null && a.link == s.link, a, s, rank)
    }

    /** Короткая запись доводов для журнала. */
    fun describe(c: Candidate): String = terms(c).filter { it.value != 0.0 }
        .joinToString(" ") { it.name + (if (it.value > 0) "+" else "") + Math.round(it.value) }
}
