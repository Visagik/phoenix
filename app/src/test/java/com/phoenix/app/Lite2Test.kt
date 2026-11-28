package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Lite2Test {
    private fun dir(): File = java.nio.file.Files.createTempDirectory("lite2").toFile()

    @Test fun atomicWriteWithoutNioReplacesOldFile() {
        val d = dir(); val f = File(d, "a.txt")
        f.writeText("old")
        AtomicWrite.write(f, "new".toByteArray(), useNio = false)
        assertEquals("new", f.readText())
        assertTrue(d.list()!!.none { it.endsWith(AtomicWrite.SUFFIX) })
    }

    @Test fun atomicWriteWithNioReplacesOldFile() {
        val d = dir(); val f = File(d, "a.txt")
        f.writeText("old")
        AtomicWrite.write(f, "new2".toByteArray(), useNio = true)
        assertEquals("new2", f.readText())
        assertTrue(d.list()!!.none { it.endsWith(AtomicWrite.SUFFIX) })
    }

    @Test fun b64MatchesJdk() {
        val rnd = java.util.Random(7)
        for (n in 0..70) {
            val b = ByteArray(n).also { rnd.nextBytes(it) }
            val url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
            assertEquals(url, B64.encodeUrlNoPad(b))
            assertTrue(b.contentEquals(B64.decode(url)))
            assertTrue(b.contentEquals(B64.decode(java.util.Base64.getEncoder().encodeToString(b))))
        }
    }

    @Test fun b64RejectsGarbage() {
        assertTrue(try { B64.decode("ab$%"); false } catch (_: IllegalArgumentException) { true })
        assertTrue(try { B64.decode("abcde"); false } catch (_: IllegalArgumentException) { true })
    }
}
