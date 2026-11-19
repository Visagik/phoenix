package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release4Pack4Test {
    @Test fun redirectToLocalOrNonHttpIsRefused() {
        assertTrue(FetchPolicy.safeRedirect("https://raw.githubusercontent.com/a/b/main/s.txt"))
        assertTrue(FetchPolicy.safeRedirect("http://example.com/sub"))
        assertFalse(FetchPolicy.safeRedirect("http://192.168.1.1/admin"))
        assertFalse(FetchPolicy.safeRedirect("http://127.0.0.1:8080/"))
        assertFalse(FetchPolicy.safeRedirect("https://10.0.0.5/x"))
        assertFalse(FetchPolicy.safeRedirect("http://localhost/x"))
        assertFalse(FetchPolicy.safeRedirect("http://[::1]/x"))
        assertFalse(FetchPolicy.safeRedirect("file:///etc/passwd"))
        assertFalse(FetchPolicy.safeRedirect("ftp://example.com/x"))
        assertFalse(FetchPolicy.safeRedirect("http://router.local/"))
        assertTrue(FetchPolicy.safeRedirect("https://172.32.0.1/x"))
    }

    @Test fun maskHostKeepsOnlyFirstLetterAndZone() {
        assertEquals("m***.ru", LogMask.host("mail.example.ru"))
        assertEquals("l***", LogMask.host("localhost"))
    }

    @Test fun importedTargetsLoseTheirServers() {
        val line = "id1\tTG\tAPP\torg.telegram\thttps://t.me\t0\t1\tvless://x@1.2.3.4:443#a|phoenix:direct"
        val cut = line.split('\t').take(7).joinToString("\t")
        assertEquals(7, cut.split('\t').size)
        assertFalse(cut.contains("phoenix:direct"))
    }

    @Test fun checkStoreModifyIsAtomicReadModifyWrite() {
        val f = java.io.File.createTempFile("checks", ".tsv"); f.delete()
        val store = CheckStore(f)
        store.putAll(mapOf("k1" to CheckResult("2026-01-01", 50, emptyMap(), -1.0, "h")), recordOdds = false)
        store.modify(recordOdds = false) { cur -> cur["k1"]?.let { mapOf("k1" to it.copy(baselineMs = 99)) } ?: emptyMap() }
        assertEquals(99L, store.load()["k1"]!!.baselineMs)
        store.modify { emptyMap() }
        assertEquals(1, store.load().size)
        f.delete()
    }

    @Test fun geminiIsJudgedByNotebookOracle() {
        assertEquals("notebook", GoogleDiag.oracleKey("gemini"))
        assertEquals("notebook", GoogleDiag.oracleKey("notebook"))
        assertEquals(null, GoogleDiag.oracleKey(null))
    }

    @Test fun checkResultServiceStatusForMatrix() {
        val r = CheckResult("2026-11-14", 100L, mapOf("TG" to 80L, "CL" to CheckServices.PARTIAL, "YT" to -1L))
        assertEquals(ServiceStatus.OPEN, r.status("TG"))
        assertEquals(ServiceStatus.PARTIAL, r.status("CL"))
        assertEquals(ServiceStatus.CLOSED, r.status("YT"))
        assertEquals(ServiceStatus.UNKNOWN, r.status("NT"))
    }

    @Test fun notebookLmHasNoGeminiAlias() {
        // у Gemini и NotebookLM регион проверяется по-разному (журнал 15.11: у половины серверов Gemini открыт, NotebookLM закрыт)
        val gem = CheckService("GE", "Gemini", "https://gemini.google.com/")
        val nt = CheckService("NT", "NotebookLM", "https://notebooklm.google.com/")
        assertEquals(null, ServiceMatch.serviceFor("https://notebooklm.google.com/", listOf(gem)))
        assertEquals(nt, ServiceMatch.serviceFor("https://notebooklm.google.com/", listOf(gem, nt)))
    }
}
