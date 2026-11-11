package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditTest {

    private fun keys(prefix: String, range: IntRange) = range.map { "vless://$prefix$it@1.1.1.$it:443" }.toSet()
    private fun set(url: String, order: Int, k: Set<String>) = SourceSet(url, order, k)

    @Test
    fun fullInclusionProposesTheSmallerOne() {
        val big = keys("a", 1..100)
        val small = big.take(30).toSet()
        val r = SourceAudit.analyze(listOf(set("big", 0, big), set("small", 1, small)))
        assertEquals(1, r.proposals.size)
        assertEquals("small", r.proposals[0].url)
        assertEquals("big", r.proposals[0].parent)
        assertEquals(ProposalKind.FULL_INCLUSION, r.proposals[0].kind)
        assertEquals(1, r.overlaps.size)
    }

    @Test
    fun identicalSetsRemoveOnlyTheLaterOne() {
        val k = keys("a", 1..50)
        val r = SourceAudit.analyze(listOf(set("first", 0, k), set("second", 1, k)))
        assertEquals(listOf("second"), r.proposals.map { it.url })
        assertEquals("first", r.proposals[0].parent)
    }

    @Test
    fun partialInclusionNeedsSmallSizeAndNinetyPercent() {
        val big = keys("a", 1..100)
        // 40 конфигов, из них 37 общих (92%) и размер 40% от большего -> предложение
        val small = big.take(37).toSet() + keys("z", 1..3)
        val ok = SourceAudit.analyze(listOf(set("big", 0, big), set("small", 1, small)))
        assertEquals(ProposalKind.PARTIAL_INCLUSION, ok.proposals.single().kind)

        // 85% включения — только строка в отчёте, без предложения
        val weak = big.take(34).toSet() + keys("z", 1..6)
        val no = SourceAudit.analyze(listOf(set("big", 0, big), set("weak", 1, weak)))
        assertTrue(no.proposals.isEmpty())
        assertEquals(1, no.overlaps.size)

        // 92% включения, но размер 80% от большего — это не «включение в меньший», и дубликатом не считается (<95%)
        val bigish = big.take(74).toSet() + keys("y", 1..6)
        val no2 = SourceAudit.analyze(listOf(set("big", 0, big), set("bigish", 1, bigish)))
        assertTrue(no2.proposals.isEmpty())
    }

    @Test
    fun nearDuplicateOfSimilarSizeIsProposed() {
        val a = keys("a", 1..100)
        val b = a.take(96).toSet() + keys("z", 1..4)           // 96% общих, размер одинаковый
        val r = SourceAudit.analyze(listOf(set("a", 0, a), set("b", 1, b)))
        assertEquals(ProposalKind.DUPLICATE, r.proposals.single().kind)
    }

    @Test
    fun tinyAndUnrelatedSourcesAreIgnored() {
        val big = keys("a", 1..100)
        val tiny = big.take(3).toSet()                           // < MIN_CONFIGS не рассматриваем
        val other = keys("q", 1..40)
        val r = SourceAudit.analyze(listOf(set("big", 0, big), set("tiny", 1, tiny), set("other", 2, other)))
        assertTrue(r.proposals.isEmpty())
        assertTrue(r.overlaps.isEmpty())
    }

    @Test
    fun selectNeverRemovesBothSidesOrADependency() {
        // A ⊂ B ⊂ C. Предложения: A->B, A->C, B->C
        val ps = listOf(
            Proposal("B", "C", ProposalKind.FULL_INCLUSION, 100),
            Proposal("A", "B", ProposalKind.FULL_INCLUSION, 100),
            Proposal("A", "C", ProposalKind.FULL_INCLUSION, 100),
        )
        val chosen = SourceAudit.select(ps, emptyList(), 5)
        // B убран (родитель C). A->B пропущено: родитель B уже убран. A->C разрешено.
        assertEquals(listOf("B", "A"), chosen.map { it.url })
        assertTrue(chosen.all { it.parent == "C" })
    }

    @Test
    fun selectRespectsLimitExistingBlacklistAndParentsInUse() {
        val ps = (1..8).map { Proposal("s$it", "P", ProposalKind.FULL_INCLUSION, 100) }
        assertEquals(5, SourceAudit.select(ps, emptyList(), 5).size)

        val existing = listOf(Blacklist.Entry("s1", "100% включение 100% в P"))
        val chosen = SourceAudit.select(ps, existing, 5).map { it.url }
        assertFalse("s1" in chosen)

        // P уже кому-то «родитель» -> убирать P нельзя
        val dependsOnP = listOf(Blacklist.Entry("old", "100% включение 100% в P"))
        val removeP = SourceAudit.select(listOf(Proposal("P", "Q", ProposalKind.FULL_INCLUSION, 100)), dependsOnP, 5)
        assertTrue(removeP.isEmpty())
    }

    @Test
    fun proposalSerializationRoundTrip() {
        val ps = listOf(
            Proposal("https://a/x", "https://b/y", ProposalKind.DUPLICATE, 97),
            Proposal("https://c/x", "https://b/y", ProposalKind.PARTIAL_INCLUSION, 91),
        )
        assertEquals(ps, SourceAudit.parse(SourceAudit.serialize(ps)))
    }

    @Test
    fun blacklistFileFormatAndOperations() {
        val f = File.createTempFile("blacklist", ".txt")
        f.writeText("# comment\nhttps://old.example/a.txt\n\nhttps://x/y # 100% включение 100% в https://z\n")
        val bl = Blacklist(f)
        assertEquals(listOf("https://old.example/a.txt", "https://x/y"), bl.load().map { it.url })
        assertEquals("100% включение 100% в https://z", bl.load()[1].reason)

        assertTrue(bl.add("https://new/z", "причина"))
        assertFalse(bl.add("https://new/z", "ещё раз"))
        assertTrue(bl.remove("https://x/y"))
        assertFalse(bl.remove("https://x/y"))
        assertEquals(setOf("https://old.example/a.txt", "https://new/z"), bl.urls())
        f.delete()
    }

    @Test
    fun userSourcesValidationAndStorage() {
        val f = File.createTempFile("mysources", ".txt"); f.delete()
        val us = UserSources(f)
        assertEquals("Нужна ссылка вида https://…", us.add("not a url"))
        assertEquals("Нужна ссылка вида https://…", us.add("ftp://x.y/z"))
        assertNull(us.add(" https://example.com/list.txt "))
        assertEquals("Эта ссылка уже в списке", us.add("https://example.com/list.txt"))
        assertEquals(listOf("https://example.com/list.txt"), us.load())
        assertTrue(us.remove("https://example.com/list.txt"))
        assertTrue(us.load().isEmpty())
        f.delete()
    }

    @Test
    fun sourceListParsing() {
        val text = "https://a.example/x.txt  # основной\r\nпросто текст\nhttps://b.example/y.txt\nhttps://a.example/x.txt\n"
        assertEquals(listOf("https://a.example/x.txt", "https://b.example/y.txt"), UserSources.parseSourceList(text))
    }
}
