package com.phoenix.app

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Результат по одному источнику. Порядок в отчёте: сначала проблемные. */
enum class SourceKind(val label: String, val order: Int) {
    PERMANENT("ПЕРМАНЕНТНО ЗАБЛОКИРОВАН", 0),
    BLOCKED("ЗАБЛОКИРОВАН", 1),
    FAIL("НЕУДАЧА", 2),
    SKIPPED("ПРОПУЩЕН (лимит времени)", 3),
    OK("УСПЕХ", 4),
}

data class SourceLine(
    val url: String,
    val kind: SourceKind,
    val detail: String = "",
    /** Источник был реактивирован в этом прогоне */
    val reactivated: Boolean = false,
)

data class GeoStats(
    val enriched: Int,
    val noHost: Int,
    val unknownCountry: Int,
    val fromNames: Int,
    val apiAttempts: Int,
    /** Страна -> число конфигов с этой меткой */
    val countries: Map<String, Int>,
)

/** Данные прогона, из которых строится отчёт. Заполняется по ходу работы Collector. */
/** Итог аудита пересечений для отчёта. */
data class AuditSummary(
    val overlaps: List<OverlapLine>,
    /** Добавлено в blacklist в этом прогоне (только 1-го и 15-го числа) */
    val applied: List<Proposal>,
    /** Предложений осталось (применяются кнопкой на вкладке «Источники») */
    val pending: Int,
    val applyDay: Boolean,
)

class ReportDraft(val startedAt: OffsetDateTime) {
    /** Пометки под заголовком отчёта («список взят из кэша» и т. п.) */
    val notes = ArrayList<String>()
    var mySourcesCount = 0
    var audit: AuditSummary? = null
    var blacklistSize = 0
    val sources = ArrayList<SourceLine>()
    var rawLines = 0
    var afterClean = 0
    var afterTop = 0
    var geo: GeoStats? = null
    var protocols: Map<String, Int> = emptyMap()
    var trackerSummary = ""
    /** null — прогон успешен */
    var failure: String? = null
    var durationMs = 0L
}

/** Построение текстового отчёта. Не зависит от Android — тестируется на JVM. */
object ReportBuilder {
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val MAX_OVERLAP_LINES = 30

    fun render(d: ReportDraft): String = buildString {
        appendLine("=== ОТЧЁТ «ФЕНИКС» ===")
        appendLine("Запуск: ${FMT.format(d.startedAt)}")
        val secs = d.durationMs / 1000
        appendLine("Длительность: ${secs / 60} мин ${secs % 60} с")
        appendLine("Итог: " + (d.failure?.let { "❌ $it" } ?: "✅ успешно"))
        d.notes.forEach { appendLine("Примечание: $it") }
        appendLine()

        // --- источники ---
        val ok = d.sources.count { it.kind == SourceKind.OK }
        val fail = d.sources.count { it.kind == SourceKind.FAIL }
        val blocked = d.sources.count { it.kind == SourceKind.BLOCKED || it.kind == SourceKind.PERMANENT }
        val permanent = d.sources.count { it.kind == SourceKind.PERMANENT }
        val skipped = d.sources.count { it.kind == SourceKind.SKIPPED }
        val reactivated = d.sources.count { it.reactivated }

        appendLine("--- Источники ---")
        appendLine("Всего: ${d.sources.size} | успешно: $ok | неудач: $fail | пропущено по времени: $skipped")
        if (d.mySourcesCount > 0) appendLine("Своих ссылок в пуле: ${d.mySourcesCount}")
        appendLine("Заблокировано: $blocked (навсегда: $permanent) | реактивировано: $reactivated")
        if (d.rawLines > 0 || d.afterTop > 0) {
            appendLine("Конфиги: строк ${d.rawLines} → после очистки ${d.afterClean} → итог ${d.afterTop} (топ-${Collector.MAX_PER_ADDRESS} на адрес)")
        }
        appendLine()
        for (s in d.sources.sortedBy { it.kind.order }) {
            val extra = if (s.detail.isNotBlank()) " (${s.detail})" else ""
            val react = if (s.reactivated) " [реактивирован]" else ""
            appendLine("${s.kind.label}: ${s.url}$extra$react")
        }

        // --- аудит пересечений ---
        d.audit?.let { a ->
            appendLine()
            appendLine("--- Пересечения источников ---")
            appendLine("В чёрном списке: ${d.blacklistSize}")
            if (a.applied.isNotEmpty()) {
                appendLine("Добавлено в чёрный список в этом прогоне (${a.applied.size}):")
                a.applied.forEach { appendLine("  + ${it.url} — ${it.reason}") }
            } else if (a.applyDay) {
                appendLine("Сегодня день автоприменения (1-е и 15-е), новых дубликатов не найдено.")
            } else {
                appendLine("Автоприменение — 1-го и 15-го числа; вручную — вкладка «Источники».")
            }
            appendLine("Ждут применения: ${a.pending}")
            if (a.overlaps.isEmpty()) {
                appendLine("Заметных пересечений (≥80%) нет.")
            } else {
                a.overlaps.take(MAX_OVERLAP_LINES).forEach { o ->
                    val kind = if (o.duplicate) "ДУБЛИКАТ" else "ВКЛЮЧЕНИЕ"
                    appendLine("$kind ${o.percent}%: ${o.small} (${o.smallCount}) ⊂ ${o.big} (${o.bigCount})")
                }
                if (a.overlaps.size > MAX_OVERLAP_LINES) appendLine("… и ещё ${a.overlaps.size - MAX_OVERLAP_LINES}")
            }
        }

        // --- GeoIP ---
        d.geo?.let { g ->
            appendLine()
            appendLine("--- Страны ---")
            val pct = if (d.afterTop > 0) g.enriched * 100 / d.afterTop else 0
            appendLine("С метками: ${g.enriched} из ${d.afterTop} ($pct%)")
            appendLine("Страна из имён: ${g.fromNames} | страна по таблице IP: ${g.apiAttempts} | без адреса: ${g.noHost} | страна неизвестна: ${g.unknownCountry}")
            if (g.countries.isNotEmpty()) {
                appendLine(
                    "Топ стран: " + g.countries.entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .take(10)
                        .joinToString(", ") { "${it.key} ${it.value}" }
                )
            }
        }

        // --- протоколы ---
        if (d.protocols.isNotEmpty()) {
            appendLine()
            appendLine("--- Протоколы ---")
            appendLine(
                d.protocols.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .joinToString(", ") { "${it.key.uppercase()} ${it.value}" }
            )
        }

        // --- трекер ---
        if (d.trackerSummary.isNotBlank()) {
            appendLine()
            appendLine("--- Трекер стабильности ---")
            appendLine(d.trackerSummary)
        }
    }
}
