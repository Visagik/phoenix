package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class Release3951Test {
    @Test fun readUpToRespectsLimit() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        for (limit in listOf(128, 4096, 65_536, 200_000)) {
            val inp = ByteArrayInputStream(data)
            val got = readUpTo(inp, limit)
            assertEquals(minOf(limit, data.size), got.size)
            assertTrue(got.indices.all { got[it] == data[it] })
            // сверх лимита не прочитано
            assertEquals(data.size - got.size, inp.available())
        }
    }

    @Test fun longHeaderLineIsSkippedNotFatal() {
        val long = "Set-Cookie: " + "a".repeat(20_000)
        val raw = "$long\r\nLocation: https://notebook.google/?location=unsupported\r\n\r\n"
        val inp = ByteArrayInputStream(raw.toByteArray())
        val budget = intArrayOf(LocalSocks.MAX_HEADERS_TOTAL)
        val first = LocalSocks.readLine(inp, strict = false, budget = budget)!!
        assertEquals(LocalSocks.MAX_LINE, first.length)
        val second = LocalSocks.readLine(inp, strict = false, budget = budget)!!
        assertEquals("Location: https://notebook.google/?location=unsupported", second)
        assertEquals("", LocalSocks.readLine(inp, strict = false, budget = budget))
    }

    @Test fun statusLineAndTotalStayLimited() {
        val long = "x".repeat(20_000) + "\n"
        var failed = false
        try { LocalSocks.readLine(ByteArrayInputStream(long.toByteArray())) } catch (_: IOException) { failed = true }
        assertTrue(failed)
        failed = false
        try { LocalSocks.readLine(ByteArrayInputStream(long.toByteArray()), strict = false, budget = intArrayOf(1000)) } catch (_: IOException) { failed = true }
        assertTrue(failed)
    }

    @Test fun reportMasksTokens() {
        val token = "abcdefghijklmnopqrstuvwxyz0123456789"
        val text = "Успешно: https://example.com/sub/$token (5 конфигов)\nДУБЛИКАТ: https://example.com/x?token=$token&a=1\n"
        val out = LogMask.applyAll(text)
        assertFalse(out.contains(token))
        assertTrue(out.contains("example.com"))
    }

    @Test fun validatorsRetainOnlyCurrent() {
        val f = File.createTempFile("val", ".tsv")
        try {
            val v = SourceValidators(f)
            v.put("https://a/1", "e1", "")
            v.put("https://b/2", "e2", "")
            v.put("https://c/3", "e3", "")
            assertEquals(1, v.retainOnly(listOf("https://a/1", "https://c/3")))
            assertTrue(v.get("https://b/2") == null)
            assertTrue(v.get("https://a/1") != null && v.get("https://c/3") != null)
        } finally { f.delete() }
    }

    @Test fun logLockIsPerFilePath() {
        val a = Storage.lockFor(File("x/phoenix-log.txt"))
        val b = Storage.lockFor(File("x/phoenix-log.txt"))
        val c = Storage.lockFor(File("x/other.txt"))
        assertTrue(a === b)
        assertTrue(a !== c)
    }
}
