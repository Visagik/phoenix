package com.phoenix.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTrimTest {
    private fun tmp(): File = File.createTempFile("log", ".txt").also { it.deleteOnExit() }

    @Test fun smallFileUntouched() {
        val f = tmp(); f.writeText("a\nb\n")
        assertFalse(LogTrim.trimIfNeeded(f, max = 100, keep = 50))
        assertEquals(listOf("a", "b"), LogTrim.tailLines(f))
    }

    @Test fun bigFileKeepsNewestWholeLines() {
        val f = tmp()
        f.writeText((1..1000).joinToString("") { "line-$it\n" })
        assertTrue(LogTrim.trimIfNeeded(f, max = 2000, keep = 500))
        val lines = LogTrim.tailLines(f)
        assertTrue(f.length() <= 500)
        assertEquals("line-1000", lines.last())
        assertTrue(lines.first().startsWith("line-"))
        assertTrue(lines.size > 30)
    }

    @Test fun missingFile() {
        assertEquals(emptyList<String>(), LogTrim.tailLines(File("/nonexistent/x.log")))
    }
}
