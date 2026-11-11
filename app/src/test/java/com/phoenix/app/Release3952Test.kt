package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class Release3952Test {
    private fun gov() = ParallelGovernor(File.createTempFile("gov", ".tsv"))

    @Test fun governorCountsParallelRuns() {
        val g = gov()
        g.begin("n", 1_000L); g.begin("n", 1_001L)
        assertEquals(2, g.activeCount("n"))
        g.finish("n", 2_000L)
        assertEquals(1, g.activeCount("n")) // второй прогон ещё идёт
        // сеть потеряла интернет, пока второй прогон идёт — это причина, уровень повышается
        assertTrue(g.outage("n", 3_000L))
        assertEquals(0, g.activeCount("n"))
    }

    @Test fun governorForgetsAbandonedRun() {
        val g = gov()
        g.begin("n", 1_000L) // отменили, finish не вызван
        val later = 1_000L + ParallelGovernor.STALE_MS + 1
        g.begin("n", later)
        assertEquals(1, g.activeCount("n"))
        g.finish("n", later + 10)
        assertEquals(0, g.activeCount("n"))
        // давно брошенный прогон не делает сбой «вероятно вызванным проверкой»
        val g2 = gov()
        g2.begin("m", 1_000L)
        assertFalse(g2.outage("m", 1_000L + ParallelGovernor.STALE_MS + ParallelGovernor.WINDOW_MS + 5))
    }

    @Test fun importAddsInOneBatch() {
        val f = File.createTempFile("src", ".txt")
        try {
            val us = UserSources(f)
            val added = us.addAll(listOf("https://a.example/sub1", "https://a.example/sub1", "bad", "https://b.example/sub2"))
            assertEquals(2, added)
            assertEquals(0, us.addAll(listOf("https://a.example/sub1")))
            assertEquals(2, us.load().size)
        } finally { f.delete() }
    }

    @Test fun jsonBracketsMustMatch() {
        assertTrue(Json.looksLikeObject("""{"a":[1,2],"b":{"c":[]}}"""))
        assertFalse(Json.looksLikeObject("""{"a":[1}}"""))
        assertFalse(Json.looksLikeObject("""{"a":{1]}"""))
        assertTrue(Json.looksLikeObject("""{"a":"]}[{"}"""))
    }

    @Test fun secondAddressIsTried() {
        val a = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val b = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        val c = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 3))
        val tried = ArrayList<String>()
        val got = ServerHosts.firstReachable(listOf(a, b, c), 3000) { x, _ -> tried += x.hostAddress!!; x == b }
        assertEquals(b, got)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), tried)
        assertNull(ServerHosts.firstReachable(listOf(a), 3000) { _, _ -> false })
    }

    @Test fun addressBudgetIsShared() {
        val a = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val b = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        var t = 0L
        val tried = ArrayList<Int>()
        val got = ServerHosts.firstReachable(listOf(a, b), 3000, { t }) { _, left -> tried += left; t += 3000; false }
        assertNull(got)
        assertEquals(listOf(3000), tried) // времени на второй адрес не осталось
    }

    @Test fun dnsCacheIsBoundedAndClearable() {
        ServerHosts.clear()
        var t = 0L
        for (i in 0 until 10) ServerHosts.reach("127.0.0.$i", 1, 50, { t })
        assertTrue(ServerHosts.cacheSize() in 1..10)
        t += 10 * 60_000L
        ServerHosts.trim(t)
        assertEquals(0, ServerHosts.cacheSize())
        ServerHosts.reach("127.0.0.1", 1, 50, { t })
        ServerHosts.clear()
        assertEquals(0, ServerHosts.cacheSize())
    }

    @Test fun spareLatencyIsNotShownAsZero() {
        assertEquals("120 мс", msText(120))
        assertTrue(msText(0).contains("не измерялась"))
    }

    @Test fun abortReasonMatchesFacts() {
        assertEquals("сменились сеть или режим", RunGuard.abortReason(true, 1328, 278))
        assertTrue(RunGuard.abortReason(false, 300, 0).contains("ни один"))
        // сеть мигнула и вернулась, живых 278 — нельзя писать «ни один не ответил»
        assertFalse(RunGuard.abortReason(false, 1328, 278).contains("ни один"))
    }
}
