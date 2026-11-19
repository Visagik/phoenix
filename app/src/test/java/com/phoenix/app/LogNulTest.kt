package com.phoenix.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogNulTest {
    @Test fun withoutNulDropsOnlyZeroBytes() {
        assertEquals("ab", String(LogTrim.withoutNul(byteArrayOf(0, 'a'.code.toByte(), 0, 0, 'b'.code.toByte()))))
    }

    @Test fun tailLinesIgnoresNulPadding() {
        val f = File.createTempFile("lognul", ".log")
        try {
            f.writeBytes(ByteArray(300) + "2026-11-19 FIX: раз\n2026-11-19 FIX: два\n".toByteArray())
            val lines = LogTrim.tailLines(f)
            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("2026"))
        } finally { f.delete() }
    }
}
