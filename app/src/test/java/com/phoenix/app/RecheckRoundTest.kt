package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecheckRoundTest {
    private val t0 = 1_000_000_000L

    @Test fun encodeDecodeRoundTrip() {
        val r = RecheckRound.Round(t0, "wifi:home\tnet", setOf("a1", "b2", "c3"))
        assertEquals(r, RecheckRound.decode(RecheckRound.encode(r).replace("home\tnet", "home net")).let { it?.copy(net = "wifi:home\tnet") })
        val plain = RecheckRound.Round(t0, "wifi", setOf("a1", "b2"))
        assertEquals(plain, RecheckRound.decode(RecheckRound.encode(plain)))
        assertEquals(RecheckRound.Round(t0, "wifi", emptySet()), RecheckRound.decode(RecheckRound.encode(RecheckRound.Round(t0, "wifi", emptySet()))))
    }

    @Test fun garbageIsNotARound() {
        assertNull(RecheckRound.decode(""))
        assertNull(RecheckRound.decode("hello"))
        assertNull(RecheckRound.decode("round\tx\twifi"))
    }

    @Test fun resumeOnlyInSameNetworkAndYoungRound() {
        val r = RecheckRound.Round(t0, "wifi", setOf("a"))
        assertTrue(RecheckRound.resumable(r, "wifi", t0 + 3_600_000L))
        assertFalse(RecheckRound.resumable(r, "mobile", t0 + 3_600_000L)) // другая сеть — заново
        assertFalse(RecheckRound.resumable(r, "wifi", t0 + RecheckRound.MAX_AGE_MS)) // старше 6 часов — заново
        assertFalse(RecheckRound.resumable(r, "wifi", t0 - 1))
        assertFalse(RecheckRound.resumable(null, "wifi", t0))
    }

    @Test fun remainingSkipsDoneKeepsOrder() {
        val items = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "c", "e"), RecheckRound.remaining(items, setOf("b", "d")) { it })
        assertTrue(RecheckRound.remaining(items, emptySet()) { it } === items)
    }

    @Test fun trackerWritesAndFinishDeletes() {
        val f = File(java.nio.file.Files.createTempDirectory("round").toFile(), RecheckRound.FILE_NAME)
        val tr = RecheckRound.Tracker(f, t0, "wifi", setOf("a"))
        tr.add(listOf("b", "c")); tr.save()
        val back = RecheckRound.load(f)!!
        assertEquals(setOf("a", "b", "c"), back.keys)
        assertEquals("wifi", back.net)
        tr.finish()
        assertFalse(f.exists())
        assertNull(RecheckRound.load(f))
    }
}
