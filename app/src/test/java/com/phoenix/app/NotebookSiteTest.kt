package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookSiteTest {
    @Test
    fun allNotebookAddressesAreOneSite() {
        assertTrue(ServiceVeto.sameSite("notebooklm.google.com", "notebook.google"))
        assertTrue(ServiceVeto.sameSite("notebook.google", "notebooklm.google"))
        assertFalse(ServiceVeto.sameSite("notebook.google", "gemini.google.com"))
    }

    @Test
    fun serviceWithOtherNotebookAddressIsMatched() {
        val nt = CheckService("NT", "Notebook", "https://notebook.google/")
        val tg = CheckService("TG", "Telegram", "https://web.telegram.org/")
        val found = ServiceMatch.serviceFor("https://notebooklm.google.com/", listOf(tg, nt))
        assertNotNull(found)
        assertEquals("NT", found!!.id)
    }

    @Test
    fun vetoedCellIsCrossed() {
        val nt = CheckService("NT", "Notebook", "https://notebook.google/")
        val check = CheckResult("2026-11-19", 100L, mapOf("NT" to 300L))
        assertEquals("✓", ServerChoice.cells(check, listOf(nt), odds = null)[0].mark)
        assertEquals("✗", ServerChoice.cells(check, listOf(nt), vetoed = { true }, odds = null)[0].mark)
    }

    @Test
    fun regionCheckOfAnyNotebookAddressUsesOriginalUrl() {
        assertEquals("https://notebooklm.google.com/", GoogleAi.regionUrl("https://notebook.google/"))
        assertEquals("https://notebooklm.google.com/", GoogleAi.regionUrl("https://notebooklm.google/"))
        assertEquals("https://claude.ai/", GoogleAi.regionUrl("https://claude.ai/"))
    }
}
