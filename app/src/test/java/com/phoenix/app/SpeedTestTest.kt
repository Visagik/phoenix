package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class SpeedTestTest {
    /** Поток, каждое чтение которого «занимает» заданное время на подменённых часах. */
    private class FakeStream(
        private val chunks: Int,
        private val chunkBytes: Int,
        private val nsPerChunk: Long,
        private val clock: LongArray,
        private val failAfter: Int = -1,
    ) : InputStream() {
        var served = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (failAfter in 0..served) throw IOException("reset")
            if (served >= chunks) return -1
            served++
            clock[0] += nsPerChunk
            return minOf(chunkBytes, len)
        }
    }

    @Test
    fun speedIsCountedAfterFirstChunk() {
        val clock = longArrayOf(0)
        // 64 КБ каждые 10 мс = 52.4 Мбит/с; первый блок не считается
        val r = SpeedTest.measure({ FakeStream(100, 65536, 10_000_000, clock) }, nanoTime = { clock[0] })
        assertNotNull(r)
        assertEquals(52.4288, r!!.mbps, 0.5)
        assertEquals(10L, r.ttfbMs)
        assertEquals(99L * 65536, r.bytes)
    }

    @Test
    fun stopsByTimeLimit() {
        val clock = longArrayOf(0)
        val r = SpeedTest.measure({ FakeStream(100_000, 65536, 10_000_000, clock) }, maxMs = 1000, nanoTime = { clock[0] })!!
        assertTrue(r.millis in 1000..1020)
    }

    @Test
    fun stopsBySizeLimit() {
        val clock = longArrayOf(0)
        val r = SpeedTest.measure({ FakeStream(100_000, 65536, 1_000_000, clock) }, maxBytes = 1_000_000, nanoTime = { clock[0] })!!
        assertTrue(r.bytes in 1_000_000..1_070_000)
    }

    @Test
    fun tooLittleDataGivesNoResult() {
        val clock = longArrayOf(0)
        assertNull(SpeedTest.measure({ FakeStream(1, 1000, 1_000_000, clock) }, nanoTime = { clock[0] }))
        assertNull(SpeedTest.measure({ FakeStream(0, 1000, 1_000_000, clock) }, nanoTime = { clock[0] }))
    }

    @Test
    fun brokenConnectionUsesWhatArrived() {
        val clock = longArrayOf(0)
        val r = SpeedTest.measure({ FakeStream(100, 65536, 10_000_000, clock, failAfter = 20) }, nanoTime = { clock[0] })
        assertNotNull(r)
        assertTrue(r!!.bytes in 1_000_000..1_400_000)
    }

    @Test
    fun formatting() {
        assertEquals("12.3 Мбит/с", SpeedTest.format(12.34))
        assertEquals("—", SpeedTest.format(-1.0))
    }
}
