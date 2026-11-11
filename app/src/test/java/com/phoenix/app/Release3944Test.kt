package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class Release3944Test {
    @Test fun parallelIdenticalProbesRunOnce() {
        ProbeShare.reset()
        val runs = AtomicInteger()
        val start = CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val threads = (1..5).map {
            Thread { start.await(); results += ProbeShare.measure("cfg-A", "https://x/") { runs.incrementAndGet(); Thread.sleep(300); 120L } }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, runs.get())
        assertEquals(listOf(120L, 120L, 120L, 120L, 120L), results.toList())
    }

    @Test fun freshResultIsReusedStaleIsNot() {
        ProbeShare.reset()
        var now = 1_000L
        ProbeShare.clock = { now }
        val runs = AtomicInteger()
        try {
            ProbeShare.measure("c", "u") { runs.incrementAndGet(); 50L }
            now += ProbeShare.OK_TTL_MS - 1
            ProbeShare.measure("c", "u") { runs.incrementAndGet(); 50L }
            assertEquals(1, runs.get())
            now += 2
            ProbeShare.measure("c", "u") { runs.incrementAndGet(); 60L }
            assertEquals(2, runs.get())
        } finally {
            ProbeShare.clock = { System.currentTimeMillis() }
        }
    }

    @Test fun failuresAreRememberedOnlyBriefly() {
        ProbeShare.reset()
        var now = 0L
        ProbeShare.clock = { now }
        val runs = AtomicInteger()
        try {
            ProbeShare.measure("c", "u") { runs.incrementAndGet(); -1L }
            now += ProbeShare.FAIL_TTL_MS - 1
            assertEquals(-1L, ProbeShare.measure("c", "u") { runs.incrementAndGet(); 40L })
            now += 2
            assertEquals(40L, ProbeShare.measure("c", "u") { runs.incrementAndGet(); 40L })
            assertEquals(2, runs.get())
        } finally {
            ProbeShare.clock = { System.currentTimeMillis() }
        }
    }

    @Test fun differentConfigsOrUrlsAreIndependent() {
        ProbeShare.reset()
        val runs = AtomicInteger()
        ProbeShare.measure("a", "u") { runs.incrementAndGet(); 1L }
        ProbeShare.measure("b", "u") { runs.incrementAndGet(); 1L }
        ProbeShare.measure("a", "v") { runs.incrementAndGet(); 1L }
        assertEquals(3, runs.get())
    }

    @Test fun exceptionDoesNotPoisonTheSlot() {
        ProbeShare.reset()
        try { ProbeShare.measure("c", "u") { throw IllegalStateException("x") } } catch (_: IllegalStateException) { }
        assertEquals(7L, ProbeShare.measure("c", "u") { 7L })
        assertTrue(ProbeShare.saved >= 0)
    }
}
