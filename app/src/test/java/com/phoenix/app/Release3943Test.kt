package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3943Test {
    private val h = 3_600_000L
    private val net = "wifi"

    private fun rec(m: Map<String, LearnEntry>, id: String, ok: Boolean, t: Long) = DirectLearn.record(m, net, id, ok, t)

    @Test fun unknownIsDueAndNotBlocked() {
        assertTrue(DirectLearn.due(null, 0))
        assertFalse(DirectLearn.blocked(null))
    }

    @Test fun threeSuccessesInARowMakeServiceTrustedAndSkippedUntilProactiveTime() {
        var m = emptyMap<String, LearnEntry>()
        m = rec(m, "steam", true, 0); m = rec(m, "steam", true, h); m = rec(m, "steam", true, 2 * h)
        val e = DirectLearn.entry(m, net, "steam")
        assertTrue(DirectLearn.trusted(e, 2 * h))
        assertFalse(DirectLearn.due(e, 3 * h))
        assertFalse(DirectLearn.due(e, 2 * h + DirectLearn.PROACTIVE_MS - 1))
        assertTrue(DirectLearn.due(e, 2 * h + DirectLearn.PROACTIVE_MS))
    }

    @Test fun untrustedIsCheckedEveryTime() {
        val m = rec(emptyMap(), "vk", true, 0)
        assertTrue(DirectLearn.due(DirectLearn.entry(m, net, "vk"), 1))
    }

    @Test fun failureResetsTrustAndTwoFailuresSendThroughVpn() {
        var m = emptyMap<String, LearnEntry>()
        repeat(3) { m = rec(m, "vk", true, it * h) }
        m = rec(m, "vk", false, 4 * h)
        val e1 = DirectLearn.entry(m, net, "vk")
        assertFalse(DirectLearn.trusted(e1, 4 * h))
        assertEquals(0, e1!!.okRun)
        assertFalse(DirectLearn.blocked(e1))
        m = rec(m, "vk", false, 5 * h)
        assertTrue(DirectLearn.blocked(DirectLearn.entry(m, net, "vk")))
        assertEquals(setOf("vk"), DirectLearn.blockedIds(m, net))
        // вернулось — сразу снова в обход
        m = rec(m, "vk", true, 6 * h)
        assertFalse(DirectLearn.blocked(DirectLearn.entry(m, net, "vk")))
    }

    @Test fun knowledgeIsPerNetwork() {
        var m = DirectLearn.record(emptyMap(), "wifi", "vk", false, 0)
        m = DirectLearn.record(m, "wifi", "vk", false, 1)
        assertTrue("vk" in DirectLearn.blockedIds(m, "wifi"))
        assertTrue(DirectLearn.blockedIds(m, "mobile:megafon").isEmpty())
        assertTrue(DirectLearn.blockedIds(DirectLearn.forgetNet(m, "wifi"), "wifi").isEmpty())
    }

    @Test fun oldKnowledgeIsNotTrusted() {
        var m = emptyMap<String, LearnEntry>()
        repeat(3) { m = rec(m, "ok", true, it.toLong()) }
        assertFalse(DirectLearn.trusted(DirectLearn.entry(m, net, "ok"), DirectLearn.STALE_MS + 10))
    }

    @Test fun savesAndLoads() {
        val f = File.createTempFile("lrn", ".tsv")
        val m = rec(rec(emptyMap(), "a", true, 5), "b", false, 6)
        DirectLearn.save(f, m)
        assertEquals(m, DirectLearn.load(f))
        f.delete()
    }

    @Test fun russianGroupDownOnlyWhenAllKnownBlocked() {
        var m = emptyMap<String, LearnEntry>()
        listOf("a", "b").forEach { id -> m = rec(rec(m, id, false, 0), id, false, 1) }
        assertTrue(DirectLearn.groupDown(m, net, listOf("a", "b")))
        assertFalse(DirectLearn.groupDown(m, net, listOf("a", "b", "c")))
    }

    @Test fun bypassStoreUsesLearnedBlocksOnlyForItsNetwork() {
        val dir = File.createTempFile("bsl", "d").also { it.delete(); it.mkdirs() }
        val here = BypassStore(dir, null, "wifi")
        var m = DirectLearn.record(emptyMap(), "wifi", "sber", false, 0)
        m = DirectLearn.record(m, "wifi", "sber", false, 1)
        DirectLearn.save(here.learnFile, m)
        assertFalse("ru.sberbankmobile" in BypassStore(dir, null, "wifi").effective())
        assertTrue("ru.sberbankmobile" in BypassStore(dir, null, "mobile").effective())
        assertNotNull(BypassStore(dir, null, "wifi").learnFile)
        dir.deleteRecursively()
    }
}
