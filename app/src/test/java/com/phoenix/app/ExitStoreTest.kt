package com.phoenix.app

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitStoreTest {
    @Test fun storesAndExpires() {
        val f = File.createTempFile("exit", ".tsv"); f.deleteOnExit()
        val s = ExitStore(f)
        s.put("k1", "nl", "2026-10-24")
        s.put("k2", "de", "2026-10-10")
        val today = LocalDate.parse("2026-10-25")
        assertEquals("NL", s.fresh("k1", today))
        assertNull(s.fresh("k2", today))
        assertNull(s.fresh("none", today))
    }

    @Test fun keepsMostRecentWhenOverLimit() {
        val f = File.createTempFile("exit", ".tsv"); f.deleteOnExit()
        val s = ExitStore(f)
        s.putAll((1..ExitStore.MAX + 50).associate { "k$it" to ExitStore.Exit("US", "2026-10-%02d".format(1 + it % 28)) })
        assertEquals(ExitStore.MAX, s.load().size)
    }

    @Test fun checkStoreCacheSeesOwnAndExternalWrites() {
        val f = File.createTempFile("checks", ".tsv"); f.deleteOnExit()
        val st = CheckStore(f)
        assertEquals(0, st.load().size)
        st.putAll(mapOf("a" to CheckResult("2026-10-24", 100, mapOf("TG" to 5L))), recordOdds = false)
        assertEquals(1, st.load().size)
        f.appendText("b\t2026-10-24\t200\tTG=7\t-1.0\thost\n")
        f.setLastModified(f.lastModified() + 5_000)
        assertEquals(2, st.load().size)
        st.putAll(mapOf("c" to CheckResult("2026-10-24", 300, emptyMap())), recordOdds = false)
        assertEquals(3, st.load().size)
    }
}
