package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class Release3948Test {
    @Test fun probeKeyDiffersForDifferentConfigsWithSameHash() {
        // "Aa" и "BB" имеют одинаковый String.hashCode() и длину
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertTrue(ProbeShare.keyOf("Aa", "https://x/") != ProbeShare.keyOf("BB", "https://x/"))
        assertEquals(ProbeShare.keyOf("Aa", "https://x/"), ProbeShare.keyOf("Aa", "https://x/"))
    }

    @Test fun collidingConfigsDoNotShareResult() {
        ProbeShare.reset()
        assertEquals(10L, ProbeShare.measure("Aa", "u") { 10L })
        assertEquals(20L, ProbeShare.measure("BB", "u") { 20L })
    }

    private fun cand(id: String) = Candidate(link = "vless://$id@h$id:443", identity = id, days = 1, mbps = 1.0, latencyMs = 100)

    @Test fun narrowServerGoesAfterOthersForGenericTarget() {
        val narrow = cand("n"); val ok = cand("o"); val unknown = cand("u")
        val checks = mapOf(
            CheckStore.keyOf(narrow.link) to CheckResult("2026-11-05", 100, mapOf("TG" to 100L, "QW" to -1L, "CL" to -1L, "YT" to -1L).mapValues { if (it.key == "TG") -1L else it.value }),
            CheckStore.keyOf(ok.link) to CheckResult("2026-11-05", 100, mapOf("TG" to 120L, "QW" to -1L, "CL" to 90L)),
        )
        val today = java.time.LocalDate.parse("2026-11-06")
        assertTrue(ServiceMatch.narrow(narrow, checks, today))
        assertFalse(ServiceMatch.narrow(ok, checks, today))
        assertFalse(ServiceMatch.narrow(unknown, checks, today)) // не проверялся — не «узкий»
        val order = ServiceMatch.order(listOf(narrow, ok, unknown), checks, null, today).map { it.identity }
        assertEquals(listOf("o", "u", "n"), order)
    }

    @Test fun fewMeasurementsAreNotEnoughToCallNarrow() {
        val c = cand("x")
        val checks = mapOf(CheckStore.keyOf(c.link) to CheckResult("2026-11-05", 100, mapOf("TG" to -1L, "QW" to -1L)))
        assertFalse(ServiceMatch.narrow(c, checks, java.time.LocalDate.parse("2026-11-06")))
    }

    @Test fun armStoreSavesAfterTenEvents() {
        val f = java.io.File(java.nio.file.Files.createTempDirectory("arm48").toFile(), "b.tsv")
        val s = ArmStore(f)
        repeat(9) { s.record("wifi", "m", "vless://x@h:1", true, 1_000L + it) }
        assertFalse(f.exists() && f.length() > 0 && ArmStore(f).arm("wifi", "m", "vless://x@h:1").total == 9)
        s.record("wifi", "m", "vless://x@h:1", true, 2_000L)
        assertEquals(10, ArmStore(f).arm("wifi", "m", "vless://x@h:1").total)
    }
}
