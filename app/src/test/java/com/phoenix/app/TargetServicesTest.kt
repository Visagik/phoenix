package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetServicesTest {
    private fun t(id: String, label: String, url: String) = Target(id, TargetKind.APP, id, label, url)

    @Test
    fun camelCaseGivesInitials() {
        assertEquals("YT", TargetAbbrev.of("YouTube"))
        assertEquals("YS", TargetAbbrev.of("YouShorts"))
        assertEquals("NL", TargetAbbrev.of("NotebookLM"))
        assertEquals("SD", TargetAbbrev.of("Steam Deck"))
    }

    @Test
    fun knownNamesGetFamiliarCodes() {
        assertEquals("TG", TargetAbbrev.of("Telegram"))
        assertEquals("DC", TargetAbbrev.of("Discord"))
        assertEquals("GK", TargetAbbrev.of("Grok"))
        assertEquals("GPT", TargetAbbrev.of("ChatGPT"))
        assertEquals(listOf("GM", "GML"), TargetAbbrev.codes(listOf("Gemini", "Gmail")))
        assertEquals("WA", TargetAbbrev.of("WhatsApp"))
        assertEquals("YM", TargetAbbrev.of("YouTube Music"))
    }

    @Test
    fun singleWordGivesTwoLetters() {
        assertEquals("ST", TargetAbbrev.of("Steam"))
        assertEquals("GM", TargetAbbrev.of("Gimp"))
        assertEquals("ST", TargetAbbrev.of("Starlink"))
        assertEquals("NT", TargetAbbrev.of("Notepad"))
        assertEquals("?", TargetAbbrev.of("🛸"))
    }

    @Test
    fun clashesGetThreeLettersThenDigit() {
        assertEquals(listOf("STE", "STA"), TargetAbbrev.codes(listOf("Steam", "Starlink")))
        assertEquals(listOf("YT", "YS"), TargetAbbrev.codes(listOf("YouTube", "YouShorts")))
        assertEquals(listOf("ABC1", "ABC2"), TargetAbbrev.codes(listOf("Abcd", "Abce")))
    }

    @Test
    fun servicesComeFromTargetsAndShareSameUrl() {
        val list = TargetServices.from(listOf(
            t("a", "Telegram", "https://web.telegram.org/"),
            t("b", "Telegram X", "https://web.telegram.org/"),
            t("c", "Steam", "https://store.steampowered.com/"),
        ))
        assertEquals(listOf("a", "c"), list.map { it.id })
        assertEquals("TG", list[0].legacyId)
        assertEquals("ST", list[1].legacyId)
    }

    @Test
    fun oldResultsAreFoundByLegacyId() {
        val s = TargetServices.from(listOf(t("a", "Telegram", "https://web.telegram.org/")))[0]
        val r = CheckResult("2026-11-20", 100L, mapOf("TG" to 250L))
        assertEquals(ServiceStatus.OPEN, r.status(s))
        assertEquals(250L, r.ms(s))
    }
}
