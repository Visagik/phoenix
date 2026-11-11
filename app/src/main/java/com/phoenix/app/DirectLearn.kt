package com.phoenix.app

import java.io.File

/** Что известно про сервис в одной сети: сколько проверок подряд открылось напрямую, сколько нет, когда проверяли в последний раз. */
data class LearnEntry(val okRun: Int = 0, val failRun: Int = 0, val lastAt: Long = 0L)

/**
 * Самообучение списка обхода по сетям. Что открывается без VPN в этой сети — идёт без VPN; результат хранится отдельно для каждой сети
 * (дома и в мобильной сети блокировки разные).
 *  - открылось напрямую → сервис сразу идёт мимо VPN;
 *  - [TRUST_AFTER] удачных проверок подряд → «доверенный»: пока ничего не меняется, его не проверяют, только проактивно раз в [PROACTIVE_MS];
 *  - не открылось [FAILS_TO_BLOCK] раза подряд → через VPN, доверие и счётчик удачных сбрасываются;
 *  - сведения старше [STALE_MS] доверия не заслуживают: проверяются заново.
 */
object DirectLearn {
    const val TRUST_AFTER = 3
    const val FAILS_TO_BLOCK = 2
    const val PROACTIVE_MS = 12 * 3_600_000L
    const val STALE_MS = 7 * 24 * 3_600_000L

    private fun key(net: String, id: String) = "$net|$id"

    fun load(file: File): Map<String, LearnEntry> {
        if (!file.exists()) return emptyMap()
        val out = LinkedHashMap<String, LearnEntry>()
        for (line in try { file.readLines() } catch (_: Exception) { emptyList() }) {
            val p = line.split('\t')
            if (p.size < 4) continue
            out[p[0]] = LearnEntry(p[1].toIntOrNull() ?: continue, p[2].toIntOrNull() ?: continue, p[3].toLongOrNull() ?: continue)
        }
        return out
    }

    fun save(file: File, map: Map<String, LearnEntry>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomic(map.entries.joinToString("") { (k, v) -> "$k\t${v.okRun}\t${v.failRun}\t${v.lastAt}\n" })
    }

    fun entry(map: Map<String, LearnEntry>, net: String, id: String): LearnEntry? = map[key(net, id)]

    /** Результат проверки «открывается напрямую»; неудача обнуляет удачные (доверие пропадает). */
    fun record(map: Map<String, LearnEntry>, net: String, id: String, ok: Boolean, now: Long): Map<String, LearnEntry> {
        val old = map[key(net, id)] ?: LearnEntry()
        val next = if (ok) LearnEntry(old.okRun + 1, 0, now) else LearnEntry(0, old.failRun + 1, now)
        return map + (key(net, id) to next)
    }

    fun blocked(e: LearnEntry?): Boolean = e != null && e.failRun >= FAILS_TO_BLOCK

    fun trusted(e: LearnEntry?, now: Long): Boolean = e != null && e.okRun >= TRUST_AFTER && now - e.lastAt < STALE_MS

    /** Нужна ли проверка сейчас: неизвестное, недоверенное и давно не проверявшееся (проактивно) проверяем, доверенное — нет. */
    fun due(e: LearnEntry?, now: Long): Boolean = e == null || !trusted(e, now) || now - e.lastAt >= PROACTIVE_MS

    fun blockedIds(map: Map<String, LearnEntry>, net: String): Set<String> =
        map.filter { (k, v) -> k.startsWith("$net|") && blocked(v) }.keys.map { it.substringAfter('|') }.toSet()

    /** Все российские сервисы известны в этой сети и ни один не открывается напрямую: найденные на телефоне российские приложения тоже пускаем через VPN. */
    fun groupDown(map: Map<String, LearnEntry>, net: String, ids: Collection<String>): Boolean =
        ids.isNotEmpty() && ids.all { blocked(map[key(net, it)]) }

    /** Забыть всё про сеть (например, сменился тип блокировок). */
    fun forgetNet(map: Map<String, LearnEntry>, net: String): Map<String, LearnEntry> = map.filterKeys { !it.startsWith("$net|") }
}
