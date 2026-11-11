package com.phoenix.app

import java.io.File

/** Набор конфигов одного успешно скачанного источника (ключ = ссылка без #имени). */
data class SourceSet(val url: String, val order: Int, val keys: Set<String>)

enum class ProposalKind(val label: String, val rank: Int) {
    FULL_INCLUSION("100% включение", 0),
    DUPLICATE("дубликат", 1),
    PARTIAL_INCLUSION("включение", 2),
}

/** «Источник [url] можно не скачивать: его конфиги уже есть в [parent]». */
data class Proposal(val url: String, val parent: String, val kind: ProposalKind, val percent: Int) {
    val reason: String get() = "${kind.label} $percent% в $parent"
}

data class OverlapLine(
    val small: String,
    val big: String,
    val percent: Int,
    val smallCount: Int,
    val bigCount: Int,
    val duplicate: Boolean,
)

class AuditResult(val proposals: List<Proposal>, val overlaps: List<OverlapLine>)

/**
 * Аудит пересечений между источниками (бывшие dedup_check.sh + dedup_auto_apply.sh).
 *
 * Сравниваются только источники, скачанные в ЭТОМ прогоне, — никакого устаревшего кэша
 * (именно из-за кэша старый аудит однажды стёр 11 записей из blacklist).
 */
object SourceAudit {
    const val MIN_CONFIGS = 5
    const val MAX_APPLY = 5

    fun analyze(all: List<SourceSet>): AuditResult {
        val src = all.filter { it.keys.size >= MIN_CONFIGS }.sortedBy { it.order }
        val overlaps = ArrayList<OverlapLine>()
        val cands = ArrayList<Pair<SourceSet, Proposal>>()

        for (i in src.indices) {
            for (j in i + 1 until src.size) {
                val a = src[i]
                val b = src[j]
                // small — тот, в ком конфигов меньше; при равенстве — тот, что стоит в списке позже.
                val aIsSmall = a.keys.size < b.keys.size
                val small = if (aIsSmall) a else b
                val big = if (aIsSmall) b else a

                val common = small.keys.count { it in big.keys }
                val inclusion = common * 100 / small.keys.size
                val ratio = small.keys.size * 100 / big.keys.size
                val overlapOfBig = common * 100 / big.keys.size
                val duplicate = ratio >= 80 && overlapOfBig >= 90

                if (inclusion >= 80) {
                    overlaps += OverlapLine(small.url, big.url, inclusion, small.keys.size, big.keys.size, duplicate)
                }

                val proposal = when {
                    inclusion == 100 -> Proposal(small.url, big.url, ProposalKind.FULL_INCLUSION, 100)
                    duplicate && overlapOfBig >= 95 -> Proposal(small.url, big.url, ProposalKind.DUPLICATE, overlapOfBig)
                    inclusion >= 90 && ratio < 50 -> Proposal(small.url, big.url, ProposalKind.PARTIAL_INCLUSION, inclusion)
                    else -> null
                }
                if (proposal != null) cands += small to proposal
            }
        }

        // По одному предложению на источник — самое сильное.
        val best = cands
            .sortedWith(
                compareBy<Pair<SourceSet, Proposal>> { it.second.kind.rank }
                    .thenByDescending { it.second.percent }
                    .thenBy { it.first.order }
            )
            .map { it.second }
            .distinctBy { it.url }

        overlaps.sortWith(compareByDescending<OverlapLine> { it.percent }.thenByDescending { it.bigCount })
        return AuditResult(best, overlaps)
    }

    /**
     * Какие из предложений можно применить сейчас (не больше [limit]).
     * Защита: у каждого убираемого источника остаётся «родитель», который сам остаётся в работе:
     *  - родитель не в blacklist;
     *  - источник не является чьим-либо родителем (иначе у того пропадёт прикрытие);
     *  - среди выбранных за этот раз тоже нет таких зависимостей;
     *  - уже лежащее в blacklist повторно не добавляется.
     */
    fun select(proposals: List<Proposal>, existing: List<Blacklist.Entry>, limit: Int = MAX_APPLY): List<Proposal> {
        val blocked = existing.map { it.url }.toMutableSet()
        val parentsInUse = HashSet<String>()
        existing.forEach { e -> if (e.reason.isNotBlank()) parentsInUse += e.reason.substringAfterLast(" в ", "") }

        val chosen = ArrayList<Proposal>()
        for (p in proposals) {
            if (chosen.size >= limit) break
            if (p.url in blocked) continue
            if (p.parent in blocked) continue
            if (p.url in parentsInUse) continue
            chosen += p
            blocked += p.url
            parentsInUse += p.parent
        }
        return chosen
    }

    /**
     * Почему автоприменение не берёт это предложение (null — возьмёт). Нужно, чтобы пользователь видел причину,
     * а не гадал, почему после «Применить» запись осталась.
     */
    fun skipReason(p: Proposal, existing: List<Blacklist.Entry>): String? {
        val blocked = existing.map { it.url }.toSet()
        if (p.url in blocked) return "уже в чёрном списке"
        if (p.parent in blocked) return "источник, где он целиком есть, сам в чёрном списке — убрав оба, потеряем конфиги"
        val parentsInUse = existing.filter { it.reason.isNotBlank() }.map { it.reason.substringAfterLast(" в ", "") }.toSet()
        if (p.url in parentsInUse) return "на него опираются другие записи чёрного списка: их конфиги целиком есть в нём"
        return null
    }

    fun serialize(list: List<Proposal>): String =
        list.joinToString("") { "${it.url}\t${it.parent}\t${it.kind.name}\t${it.percent}\n" }

    fun parse(text: String): List<Proposal> = text.lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4) return@mapNotNull null
        val kind = ProposalKind.entries.firstOrNull { it.name == p[2] } ?: return@mapNotNull null
        Proposal(p[0], p[1], kind, p[3].toIntOrNull() ?: return@mapNotNull null)
    }
}

/**
 * Источники, которые пользователь сам вернул из чёрного списка. Аудит их больше не предлагает —
 * иначе 1-го или 15-го числа он снова убрал бы то, что человек оставил осознанно.
 */
class KeepList(private val file: File) {
    fun load(): Set<String> =
        if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()

    fun add(url: String) {
        val cur = load()
        if (url !in cur) file.writeTextAtomic((cur + url).joinToString("") { it + "\n" })
    }

    fun remove(url: String) {
        val cur = load()
        if (url in cur) file.writeTextAtomic((cur - url).joinToString("") { it + "\n" })
    }
}

/**
 * Чёрный список источников. Формат файла: «URL # причина», по строке на запись.
 * Автоматически записи НЕ удаляются — только пополняются (урок аудита из Termux-версии).
 * Сравнение с источниками — по точному совпадению URL.
 */
class Blacklist(private val file: File) {
    data class Entry(val url: String, val reason: String)

    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val cut = line.indexOf(" #")
            if (cut < 0) Entry(line, "")
            else Entry(line.substring(0, cut).trim(), line.substring(cut + 2).trim())
        }.filter { it.url.isNotEmpty() }
    }

    fun urls(): Set<String> = load().map { it.url }.toSet()

    fun add(url: String, reason: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        val entries = load()
        if (entries.any { it.url == u }) return false
        write(entries + Entry(u, reason.trim().replace('\n', ' ')))
        return true
    }

    fun remove(url: String): Boolean {
        val entries = load()
        if (entries.none { it.url == url }) return false
        write(entries.filter { it.url != url })
        return true
    }

    private fun write(entries: List<Entry>) {
        val text = entries.joinToString("") { e ->
            (if (e.reason.isNotEmpty()) "${e.url} # ${e.reason}" else e.url) + "\n"
        }
        file.writeTextAtomic(text)
    }
}
