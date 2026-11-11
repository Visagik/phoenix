package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class Release3940Test {
    @Test fun guardNoticesNetworkOrModeChange() {
        val g = RunGuard(Mode.BLACKLIST, "wifi")
        assertFalse(g.stale(Mode.BLACKLIST, "wifi"))
        assertTrue(g.stale(Mode.BLACKLIST, "mobile:megafon pre-5g"))
        assertTrue(g.stale(Mode.WHITELIST, "wifi"))
    }

    @Test fun hopelessOnlyAfterEnoughChecksWithoutAnyAlive() {
        assertFalse(RunGuard.hopeless(RunGuard.HOPELESS_AFTER - 1, 0))
        assertTrue(RunGuard.hopeless(RunGuard.HOPELESS_AFTER, 0))
        assertFalse(RunGuard.hopeless(500, 1))
    }

    @Test fun manualHoldYieldsOnlyToPersistentContradiction() {
        assertFalse(ModeHold.overridden(ModeHold.OVERRIDE_VOTES - 1))
        assertTrue(ModeHold.overridden(ModeHold.OVERRIDE_VOTES))
    }

    @Test fun quarantineKeepsJustLeftServerAtTheEnd() {
        Quarantine.clear()
        val now = 1_000_000L
        Quarantine.add("left", now)
        assertTrue(Quarantine.active("left", now + 5 * 60_000L))
        assertFalse(Quarantine.active("left", now + Quarantine.MINUTES * 60_000L + 1))
        Quarantine.clear()
    }

    @Test fun searchStopsWhenAskedFromOutside() {
        val stop = AtomicBoolean(false)
        val s = ServerSearch(
            reach = { h, _ -> Thread.sleep(30); stop.set(true); h },
            probe = { _, _ -> Thread.sleep(200); -1L },
            tcpParallel = 2,
            probeParallel = 2,
            shouldStop = { stop.get() },
        )
        val cands = (0 until 300).map { i ->
            val l = "trojan://pw@10.0.${i / 250}.${i % 250 + 1}:443#S$i"
            Candidate(l, ConfigIdentity.key(l), 0, -1.0, 0)
        }
        val t0 = System.currentTimeMillis()
        val found = runBlocking { s.find(cands, want = 5, budgetMs = 20_000) }
        assertTrue(s.aborted)
        assertEquals(0, found.size)
        assertTrue("поиск должен прерваться быстро", System.currentTimeMillis() - t0 < 5_000)
    }
}
