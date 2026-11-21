package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Release3968SearchTest {
    private fun cand(i: Int): Candidate {
        val l = "vless://11111111-2222-3333-4444-55555555555$i@104.21.1.1:443?security=tls&sni=s$i.example.com&type=ws&path=/p$i#C$i"
        return Candidate(l, ConfigIdentity.key(l), 0, -1.0, 0)
    }

    @Test fun transportKeyDiffersBySniAndIgnoresCredentials() {
        assertTrue(ServerFingerprint.transportKey(cand(1).link) != ServerFingerprint.transportKey(cand(2).link))
        val a = "vless://aaaa@1.2.3.4:443?security=tls&sni=x.com&type=ws&path=/a#A"
        val b = "vless://bbbb@1.2.3.4:443?security=tls&sni=x.com&type=ws&path=/a#B"
        assertEquals(ServerFingerprint.transportKey(a), ServerFingerprint.transportKey(b))
    }

    @Test fun deadSiblingsBehindOneCdnAddressDoNotHideTheLiveOne() {
        val calls = AtomicInteger()
        val s = ServerSearch(
            reach = { h, _ -> h },
            probe = { _, _ -> if (calls.incrementAndGet() <= 3) -1L else 50L },
            tcpParallel = 1,
            probeParallel = 1,
            onePerHost = false,
            hostStrikes = 3,
        )
        val found = runBlocking { s.find((1..8).map(::cand), want = 1, budgetMs = 5_000) }
        assertTrue("после трёх провалов с разным SNI четвёртый должен быть проверен", calls.get() >= 4)
        assertEquals(1, found.size)
    }

    @Test fun tcpFailuresAreCountedEvenAfterFirstOpenPort() {
        val s = ServerSearch(
            reach = { h, _ -> if (h.endsWith(".1")) h else null },
            probe = { _, _ -> 40L },
            tcpParallel = 1,
            probeParallel = 1,
        )
        val cands = (0 until 20).map { i ->
            val l = "trojan://pw@203.0.${i}.${if (i == 0) 1 else 2}:443#N$i"
            Candidate(l, ConfigIdentity.key(l), 0, -1.0, 0)
        }
        runBlocking { s.find(cands, want = 100, budgetMs = 5_000) }
        assertEquals(19, s.tcpFailed)
        assertEquals(1, s.probeOkCount)
    }
}
