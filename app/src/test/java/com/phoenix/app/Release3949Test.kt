package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Release3949Test {
    private fun cand(id: String, host: String = "h$id") = Candidate(link = "vless://$id@$host:443", identity = id, days = 1, mbps = 1.0, latencyMs = 100)
    private val today = LocalDate.parse("2026-11-06")
    private fun narrowChecks(c: Candidate, date: String, host: String = ConfigIdentity.address(c.link)) =
        mapOf(CheckStore.keyOf(c.link) to CheckResult(date, 100, mapOf("TG" to -1L, "QW" to -1L, "CL" to -1L), host = host))

    @Test fun freshNarrowResultCounts() {
        val c = cand("a")
        assertTrue(ServiceMatch.narrow(c, narrowChecks(c, "2026-11-05"), today))
    }

    @Test fun oldNarrowResultIsIgnored() {
        val c = cand("a")
        assertFalse(ServiceMatch.narrow(c, narrowChecks(c, "2026-10-20"), today))
        assertFalse(ServiceMatch.narrow(c, narrowChecks(c, "не дата"), today))
    }

    @Test fun changedAddressMakesResultStale() {
        val c = cand("a")
        assertFalse(ServiceMatch.narrow(c, narrowChecks(c, "2026-11-05", host = "old.example:443"), today))
    }

    @Test fun offlineSuspectNeedsManyDistinctHosts() {
        assertTrue(ServerSearch.OFFLINE_MIN_HOSTS in 10..ServerSearch.OFFLINE_SUSPECT)
    }
}
