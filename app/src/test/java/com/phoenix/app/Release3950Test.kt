package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class Release3950Test {
    @Test fun probeShareKeepsBoundedTable() {
        ProbeShare.reset()
        var t = 0L
        ProbeShare.clock = { t }
        try {
            for (i in 0 until ProbeShare.MAX_DONE + 300) { t += 1; ProbeShare.measure("cfg$i", "u") { 10L } }
            t += 1
            ProbeShare.trim(t)
            assertTrue(ProbeShare.size() <= ProbeShare.MAX_DONE)
            // просроченные записи удаляются
            t += ProbeShare.OK_TTL_MS + 1
            ProbeShare.trim(t)
            assertEquals(0, ProbeShare.size())
        } finally { ProbeShare.clock = { System.currentTimeMillis() }; ProbeShare.reset() }
    }

    @Test fun probeShareTrimKeepsRunningProbe() {
        ProbeShare.reset()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit { ProbeShare.measure("slow", "u") { started.countDown(); release.await(); 5L } }
            started.await()
            ProbeShare.trim(System.currentTimeMillis() + 10 * ProbeShare.OK_TTL_MS)
            assertEquals(1, ProbeShare.size())
            release.countDown()
        } finally { release.countDown(); pool.shutdown() }
    }

    @Test fun probeShareConcurrentSavedIsExact() {
        ProbeShare.reset()
        val n = 50
        val pool = Executors.newFixedThreadPool(8)
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val latch = CountDownLatch(n)
        repeat(n) { pool.submit { ProbeShare.measure("same", "u") { Thread.sleep(50); runs.incrementAndGet(); 7L }; latch.countDown() } }
        latch.await()
        pool.shutdown()
        assertEquals(1, runs.get())
        assertEquals(n - 1, ProbeShare.saved)
        ProbeShare.reset()
    }
}
