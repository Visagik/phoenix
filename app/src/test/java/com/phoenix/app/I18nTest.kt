package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    private fun with(lang: String, dict: String, body: () -> Unit) {
        val oldLoader = Tr.loader
        Tr.loader = { if (it == lang) dict else null }
        Tr.override = lang
        Tr.reset()
        try { body() } finally { Tr.loader = oldLoader; Tr.override = null; Tr.reset() }
    }

    @Test fun russianIsSourceAndFillsArgs() {
        Tr.override = "ru"; Tr.reset()
        try {
            assertEquals("Собрать сейчас", tr("Собрать сейчас"))
            assertEquals("Серверов: 5 из 9", tr("Серверов: {} из {}", 5, 9))
        } finally { Tr.override = null; Tr.reset() }
    }

    @Test fun translatesAndFallsBack() {
        with("en", "Собрать сейчас\tCollect now\nСерверов: {}\tServers: {}\n") {
            assertEquals("Collect now", tr("Собрать сейчас"))
            assertEquals("Servers: 7", tr("Серверов: {}", 7))
            assertEquals("Нет перевода", tr("Нет перевода"))
        }
    }

    @Test fun argumentsCanBeReordered() {
        with("tr", "Из {} в {}\t{2} yerine {1}\n") {
            assertEquals("B yerine A", tr("Из {} в {}", "A", "B"))
        }
    }

    @Test fun escapesRoundTrip() {
        val s = "строка\nс переносом\\и косой"
        assertEquals(s, Tr.unescape(Tr.escape(s)))
        val m = Tr.parse("a\\nb\tc\\nd\n# comment\n\nbad line\n")
        assertEquals("c\nd", m["a\nb"])
        assertEquals(1, m.size)
    }

    @Test fun unsupportedLanguageUsesRussian() {
        with("de", "Привет\tHallo\n") {
            assertEquals("Привет", tr("Привет"))
        }
    }

    @Test fun rtlLanguages() {
        assertTrue(Tr.isRtl("ar"))
        assertTrue(Tr.isRtl("fa"))
        assertFalse(Tr.isRtl("en"))
    }

    @Test fun placeholdersListed() {
        assertEquals(listOf("{}", "{2}"), Tr.placeholders("а {} б {2} в"))
    }
}
