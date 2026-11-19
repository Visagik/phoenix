package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageChoiceTest {
    @Test fun knownCodesStay() {
        assertEquals("tr", LanguageChoice.clamp("tr"))
        assertEquals("Türkçe", LanguageChoice.nameOf("tr"))
    }

    @Test fun unknownOrEmptyMeansSystem() {
        assertEquals("", LanguageChoice.clamp("xx"))
        assertEquals("", LanguageChoice.clamp(null))
        assertNull(LanguageChoice.nameOf(""))
    }

    @Test fun everySupportedLanguageIsInTheList() {
        val codes = LanguageChoice.items.map { it.first }.toSet()
        assertEquals(Tr.SUPPORTED.toSet() + "ru", codes)
    }
}
