package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Р1 (один замер на всех) и Р2 (срок годности замера заранее). */
class SharedMeasureTest {
    private fun measured(tag: String) = Measured(ProbeCounts(10, 10, 4, 5), mapOf("https://x/" to tag), 0L, 1L)

    @Test fun twoConcurrentCallsRunComputeOnce() {
        val sm = SharedMeasure<Measured>()
        val calls = AtomicInteger()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val results = arrayOfNulls<Measured>(2)
        val t1 = Thread { results[0] = sm.get(1) { calls.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); measured("first") } }
        t1.start()
        assertTrue(started.await(5, TimeUnit.SECONDS)) // первый замер идёт
        val t2 = Thread { results[1] = sm.get(1) { calls.incrementAndGet(); measured("second") } }
        t2.start()
        Thread.sleep(100)
        release.countDown()
        t1.join(5000); t2.join(5000)
        assertEquals(1, calls.get())
        assertTrue(results[0] === results[1])
        assertEquals("first", results[0]!!.why.values.single()) // причины принадлежат этому же результату
    }

    @Test fun afterFinishNextCallMeasuresAgain() {
        val sm = SharedMeasure<Int>(); val calls = AtomicInteger()
        sm.get(1) { calls.incrementAndGet() }; sm.get(1) { calls.incrementAndGet() }
        assertEquals(2, calls.get())
    }

    @Test fun measureStartedInAnotherNetworkIsNotShared() {
        val sm = SharedMeasure<String>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var other: String? = null
        val t = Thread { sm.get(1) { started.countDown(); release.await(5, TimeUnit.SECONDS); "old-net" } }
        t.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
        other = sm.get(2) { "new-net" }
        release.countDown(); t.join(5000)
        assertEquals("new-net", other)
    }

    @Test fun failureOfComputeReachesWaiterAndClearsFlight() {
        val sm = SharedMeasure<Int>()
        try { sm.get(1) { throw IllegalStateException("boom") }; throw AssertionError("ждали исключение") } catch (_: IllegalStateException) { }
        assertEquals(7, sm.get(1) { 7 })
    }

    @Test fun ownerFailureReachesWaiterInAnotherThread() {
        val sm = SharedMeasure<Int>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = Thread { try { sm.get(1) { started.countDown(); release.await(5, TimeUnit.SECONDS); throw IllegalStateException("boom") } } catch (_: IllegalStateException) { } }
        owner.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
        var waiterError: Throwable? = null
        val waiter = Thread { try { sm.get(1) { 99 } } catch (t: Throwable) { waiterError = t } }
        waiter.start()
        Thread.sleep(150) // ожидающий должен успеть встать в очередь за владельцем
        release.countDown(); owner.join(5000); waiter.join(5000)
        assertTrue("ошибка владельца должна дойти до ожидающего: $waiterError", waiterError is IllegalStateException)
        assertEquals(5, sm.get(1) { 5 }) // после ошибки замер не залип
    }

    @Test fun whySummaryAndFailedList() {
        val m = Measured(ProbeCounts(8, 10, 3, 4), mapOf("https://www.sberbank.ru/" to "TLS: CertPathValidatorException", "https://a.example/" to "DNS", "https://b.example/" to "DNS"), 0, 1)
        assertEquals("TLS: CertPathValidatorException 1, DNS 2", m.whySummary())
        assertEquals("www.sberbank.ru — TLS: CertPathValidatorException", m.failedWithWhy(listOf("https://www.sberbank.ru/", "https://ya.ru/")))
    }

    @Test fun aheadUsableByAgeEpisodeAndNetwork() {
        val t0 = 1_000_000L
        assertTrue(HealthGate.aheadUsable(5, 2, t0, 5, 2, t0 + 30_000))
        assertFalse(HealthGate.aheadUsable(5, 2, t0, 5, 2, t0 + 90_000))
        assertFalse(HealthGate.aheadUsable(5, 2, t0, 5, 3, t0 + 30_000)) // другая сеть
        assertFalse(HealthGate.aheadUsable(5, 2, t0, 6, 2, t0 + 30_000)) // другой эпизод
    }
}
