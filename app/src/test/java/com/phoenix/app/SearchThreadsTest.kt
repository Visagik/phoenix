package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchThreadsTest {
    private fun link(i: Int) = "trojan://pw@10.0.${i / 250}.${i % 250 + 1}:443#Server number $i"
    private fun cand(i: Int) = Candidate(link(i), ConfigIdentity.key(link(i)), 0, -1.0, 0, rel = 0.5, modeHint = false, alive = true)

    /** Н2: пока поиск держит 76 блокирующих мест, остальной код на Dispatchers.IO не встаёт в очередь. */
    @Test fun heavySearchDoesNotStarveDispatchersIo() {
        val s = ServerSearch(
            reach = { h, _ -> Thread.sleep(250); h },
            probe = { _, _ -> Thread.sleep(250); 100L },
            tcpParallel = 48, probeParallel = 28, maxCandidates = 2000, onePerHost = false,
        )
        var searchDone = false
        val t = Thread { runBlocking { s.find((0 until 600).map { cand(it) }, want = Int.MAX_VALUE, budgetMs = 30_000) }; searchDone = true }
        t.start()
        Thread.sleep(600) // поиск уже занял все места
        val t0 = System.nanoTime()
        val v = runBlocking { withContext(Dispatchers.IO) { 1 } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, v)
        assertTrue("withContext(Dispatchers.IO) ждал $ms мс", ms < 200)
        t.join(60_000)
        assertTrue(searchDone)
        assertTrue(s.finished)
        assertEquals(600, s.triedTcp)
    }

    @Test fun finishedIsFalseWhenBudgetEnds() {
        val s = ServerSearch(reach = { h, _ -> Thread.sleep(200); h }, probe = { _, _ -> Thread.sleep(200); 100L }, tcpParallel = 2, probeParallel = 2, maxCandidates = 2000, onePerHost = false)
        runBlocking { s.find((0 until 200).map { cand(it) }, want = Int.MAX_VALUE, budgetMs = 500) }
        assertTrue(!s.finished)
    }
}
