package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankScoreTest {
    private fun c(id: String, lat: Long = 100, mbps: Double = 0.0, alive: Boolean = false, bad: Boolean = false, q: Boolean = false, prio: Int = 0, days: Int = 0) =
        Candidate("vless://$id@h$id:443", id, days, mbps, lat, alive = alive, bad = bad, quarantined = q, prio = prio)

    @Test fun aliveBeatsUnknown() = assertEquals("a", RankScore.order(listOf(c("b"), c("a", alive = true))).first().identity)

    @Test fun badAndQuarantineGoLast() {
        val o = RankScore.order(listOf(c("bad", alive = true, bad = true), c("q", alive = true, q = true), c("ok")))
        assertEquals("ok", o.first().identity)
        assertEquals("bad", o.last().identity)
    }

    @Test fun lowerLatencyWinsWhenOtherwiseEqual() = assertEquals("fast", RankScore.order(listOf(c("slow", lat = 900), c("fast", lat = 80))).first().identity)

    @Test fun highPriorityBeatsFasterUnknownSource() =
        assertEquals("hp", RankScore.order(listOf(c("fast", lat = 60, mbps = 40.0), c("hp", lat = 400, prio = 1))).first().identity)

    @Test fun compareAgreesWithRankOnPlainCases() {
        val list = Ranking.rank(listOf(c("a", alive = true, lat = 90), c("b", alive = true, lat = 300), c("c", lat = 50), c("d", alive = true, bad = true)))
        assertTrue(RankScore.compare(list).agrees)
    }

    @Test fun compareReportsRankOfActualTopInScore() {
        val v = RankScore.compare(listOf(c("x", lat = 900), c("y", alive = true)))
        assertFalse(v.agrees)
        assertEquals("y", v.scoreTop?.identity)
        assertEquals(2, v.actualRankInScore)
    }

    @Test fun describeListsNonZeroTerms() {
        val d = RankScore.describe(c("a", alive = true, prio = 1))
        assertTrue(d.contains("жив+100"))
        assertTrue(d.contains("приоритет+15"))
    }
}
