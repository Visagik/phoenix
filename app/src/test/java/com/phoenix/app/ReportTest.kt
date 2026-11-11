package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ReportTest {

    private fun draft() = ReportDraft(OffsetDateTime.parse("2026-10-13T00:05:00+03:00"))

    @Test
    fun rendersAllSectionsAndSortsProblemsFirst() {
        val d = draft()
        d.sources += SourceLine("https://ok.example/a", SourceKind.OK, "500 симв.")
        d.sources += SourceLine("https://bad.example/b", SourceKind.FAIL, "код 404, неудача #2")
        d.sources += SourceLine("https://dead.example/c", SourceKind.PERMANENT)
        d.sources += SourceLine("https://re.example/d", SourceKind.OK, "10 симв.", reactivated = true)
        d.rawLines = 100
        d.afterClean = 80
        d.afterTop = 60
        d.geo = GeoStats(45, 5, 10, 40, 5, mapOf("DE" to 20, "NL" to 25))
        d.protocols = mapOf("vless" to 50, "trojan" to 10)
        d.trackerSummary = "новых имён: 3, всего: 9, стабильных (3+ дня): 2"
        d.durationMs = 125_000

        val t = ReportBuilder.render(d)

        assertTrue(t.contains("Запуск: 2026-10-13 00:05:00"))
        assertTrue(t.contains("Длительность: 2 мин 5 с"))
        assertTrue(t.contains("Итог: ✅ успешно"))
        assertTrue(t.contains("Всего: 4 | успешно: 2 | неудач: 1 | пропущено по времени: 0"))
        assertTrue(t.contains("Заблокировано: 1 (навсегда: 1) | реактивировано: 1"))
        assertTrue(t.contains("строк 100 → после очистки 80 → итог 60"))
        assertTrue(t.contains("С метками: 45 из 60 (75%)"))
        assertTrue(t.contains("Топ стран: NL 25, DE 20"))
        assertTrue(t.contains("VLESS 50, TROJAN 10"))
        assertTrue(t.contains("[реактивирован]"))
        assertTrue(t.contains("стабильных (3+ дня): 2"))

        // проблемные источники выше успешных
        val dead = t.indexOf("https://dead.example/c")
        val bad = t.indexOf("https://bad.example/b")
        val ok = t.indexOf("https://ok.example/a")
        assertTrue(dead in 0 until bad)
        assertTrue(bad < ok)
    }

    @Test
    fun auditSectionAndNotes() {
        val d = draft()
        d.notes += "общий список источников недоступен — использован сохранённый"
        d.mySourcesCount = 2
        d.blacklistSize = 4
        d.sources += SourceLine("https://ok.example/a", SourceKind.OK, "500 симв., 60 конфигов")
        val applied = Proposal("https://sub.example/s", "https://big.example/b", ProposalKind.FULL_INCLUSION, 100)
        d.audit = AuditSummary(
            overlaps = listOf(OverlapLine("https://sub.example/s", "https://big.example/b", 100, 30, 60, false)),
            applied = listOf(applied),
            pending = 2,
            applyDay = true,
        )
        val t = ReportBuilder.render(d)
        assertTrue(t.contains("Примечание: общий список источников недоступен"))
        assertTrue(t.contains("Своих ссылок в пуле: 2"))
        assertTrue(t.contains("--- Пересечения источников ---"))
        assertTrue(t.contains("В чёрном списке: 4"))
        assertTrue(t.contains("Добавлено в чёрный список в этом прогоне (1):"))
        assertTrue(t.contains("+ https://sub.example/s — 100% включение 100% в https://big.example/b"))
        assertTrue(t.contains("Ждут применения: 2"))
        assertTrue(t.contains("ВКЛЮЧЕНИЕ 100%: https://sub.example/s (30) ⊂ https://big.example/b (60)"))

        // не день автоприменения
        d.audit = AuditSummary(emptyList(), emptyList(), 0, applyDay = false)
        val t2 = ReportBuilder.render(d)
        assertTrue(t2.contains("Автоприменение — 1-го и 15-го числа"))
        assertTrue(t2.contains("Заметных пересечений (≥80%) нет."))
    }

    @Test
    fun failureReportHasNoStatsSections() {
        val d = draft()
        d.failure = "Нет сети или список источников недоступен"
        val t = ReportBuilder.render(d)
        assertTrue(t.contains("Итог: ❌ Нет сети или список источников недоступен"))
        assertFalse(t.contains("--- Страны ---"))
        assertFalse(t.contains("--- Протоколы ---"))
        assertFalse(t.contains("--- Трекер стабильности ---"))
    }
}
