package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeStatsTest {
    @Test fun classifiesTypicalGoErrors() {
        val c = ProbeStats::classify
        assertEquals(ProbeStats.Err.TIMEOUT, c("context deadline exceeded"))
        assertEquals(ProbeStats.Err.TIMEOUT, c("Get \"https://www.gstatic.com/generate_204\": net/http: request canceled (Client.Timeout exceeded while awaiting headers)"))
        assertEquals(ProbeStats.Err.TIMEOUT, c("dial tcp 1.2.3.4:443: i/o timeout"))
        assertEquals(ProbeStats.Err.EOF, c("EOF"))
        assertEquals(ProbeStats.Err.EOF, c("read tcp 10.0.0.1:5000->1.2.3.4:443: read: connection reset by peer"))
        assertEquals(ProbeStats.Err.REFUSED, c("dial tcp 1.2.3.4:443: connect: connection refused"))
        assertEquals(ProbeStats.Err.TLS, c("tls: first record does not look like a TLS handshake"))
        assertEquals(ProbeStats.Err.STATUS, c("invalid status: 403 Forbidden"))
        assertEquals(ProbeStats.Err.STATUS, c("status != 20x: 403 Forbidden"))
        assertEquals(ProbeStats.Err.STATUS, c("status != 20x: 504 Gateway Timeout"))
        assertEquals(ProbeStats.Err.CLOSED_PIPE, c("io: read/write on closed pipe"))
        assertEquals(ProbeStats.Err.OTHER, c("something strange"))
        assertEquals(ProbeStats.Err.OTHER, c(""))
    }

    @Test fun percentilesOnSmallSamples() {
        assertNull(ProbeStats.percentile(emptyList(), 0.5))
        assertEquals(7L, ProbeStats.percentile(listOf(7L), 0.5))
        assertEquals(7L, ProbeStats.percentile(listOf(7L), 0.9))
        assertEquals(10L, ProbeStats.percentile(listOf(30L, 10L), 0.5))
        assertEquals(30L, ProbeStats.percentile(listOf(30L, 10L), 0.9))
        assertEquals(90L, ProbeStats.percentile((1L..100L).toList(), 0.9) )
    }

    @Test fun inactiveCountsNothing() {
        ProbeStats.begin(); ProbeStats.end()
        ProbeStats.record(-1, 14_000, "context deadline exceeded")
        assertTrue(ProbeStats.lines(28, "wifi").isEmpty())
    }

    @Test fun fiveLinesWithCountsAndReset() {
        ProbeStats.begin()
        ProbeStats.record(-1, 14_000, "context deadline exceeded")
        ProbeStats.record(-1, 2_000, "EOF")
        ProbeStats.record(-1, 9_000, "dial tcp 1.2.3.4:443: connection refused")
        ProbeStats.record(2600, 3_100, "")
        ProbeStats.alternative(afterTimeout = true, saved = false)
        ProbeStats.alternative(afterTimeout = true, saved = true)
        val l = ProbeStats.lines(28, "wifi")
        assertEquals(5, l.size)
        assertTrue(l[0], l[0].contains("таймаут 1") && l[0].contains("обрыв/EOF 1") && l[0].contains("отказ 1"))
        assertTrue(l[1], l[1].startsWith("примеры ошибок"))
        assertTrue(l[2], l[2].contains("до 3 с 1") && l[2].contains("3–8 с 0") && l[2].contains("8–13 с 1") && l[2].contains("дольше 13 с 1"))
        assertTrue(l[3], l[3].contains("p50 2600") && l[3].contains("параллельно проб 28, сеть wifi"))
        assertTrue(l[4], l[4].contains("попыток 2, спас 1 (из них после таймаута 1 из 2"))
        ProbeStats.begin() // сброс между проходами
        assertTrue(ProbeStats.lines(28, "wifi").isEmpty())
    }

    @Test fun examplesAreMaskedAndLimited() {
        ProbeStats.begin()
        ProbeStats.record(-1, 5000, "Get \"https://secret.example.org/sub/AbCdEfGhIjKlMnOpQrStUvWx\": dial tcp 203.0.113.7:443: i/o timeout")
        for (i in 1..20) ProbeStats.record(-1, 5000, "error number $i")
        val l = ProbeStats.lines(7, "wifi")
        val ex = l[1]
        assertTrue(ex, !ex.contains("203.0.113.7") && !ex.contains("secret.example.org") && !ex.contains("AbCdEfGhIjKlMnOpQrStUvWx"))
        assertTrue(ex, ex.contains("<ip>") || ex.contains("<host>"))
        assertTrue(ex.split(" | ").size <= 8)
        assertTrue(ProbeStats.sanitize("x".repeat(500)).length <= 120)
        assertEquals("www.gstatic.com", ProbeStats.sanitize("www.gstatic.com"))
        ProbeStats.end()
    }

    @Test fun countersSurviveManyThreads() {
        ProbeStats.begin()
        val ts = (1..8).map { Thread { repeat(500) { ProbeStats.record(-1, 5000, "EOF"); ProbeStats.record(1000, 2000, "") } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        val l = ProbeStats.lines(8, "wifi")
        assertTrue(l[0], l[0].contains("обрыв/EOF 4000"))
        ProbeStats.end()
    }
}
