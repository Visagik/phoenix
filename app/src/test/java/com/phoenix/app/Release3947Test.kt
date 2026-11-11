package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3947Test {
    private fun cand(id: String, link: String = "vless://$id@h$id:443?security=reality", rel: Double = 0.5, mbps: Double = 5.0, lat: Long = 100, bad: Boolean = false) =
        Candidate(link = link, identity = id, days = 3, mbps = mbps, latencyMs = lat, rel = rel, bad = bad, alive = true)

    @Test fun armSmoothsAndExploresRareOnes() {
        assertTrue(Arm(0, 0).ucb > Arm(5, 5).ucb) // неизвестное пробуется охотнее среднего
        assertTrue(Arm(30, 2).ucb > Arm(2, 30).ucb)
        var a = Arm(0, 0)
        repeat(200) { a = a.after(true) }
        assertTrue(a.total <= Arm.MAX_TOTAL)
    }

    @Test fun armStoreSavesAndReloads() {
        val f = File(java.nio.file.Files.createTempDirectory("arm47").toFile(), "bandit.tsv")
        val s = ArmStore(f)
        val link = "vless://x@h:443?security=reality"
        s.record("wifi", "black", link, true, 1_000_000L)
        s.record("wifi", "black", link, false, 1_000_001L)
        s.save(9_000_000L)
        val a = ArmStore(f).arm("wifi", "black", link)
        assertEquals(1, a.ok); assertEquals(1, a.fail)
        assertEquals(0, ArmStore(f).arm("mobile", "black", link).total) // другая сеть — другой опыт
    }

    @Test fun badAndQuarantinedGoLast() {
        val list = listOf(cand("a", bad = true), cand("b"), cand("c", rel = 0.9))
        val order = FitScore.order(list) { Arm(0, 0) }.map { it.identity }
        assertEquals("c", order.first()); assertEquals("a", order.last())
    }

    @Test fun learnedProtocolFailureLowersScore() {
        val reality = cand("r", "vless://r@h:443?security=reality")
        val trojan = cand("t", "trojan://t@h2:443")
        val arms = mapOf("reality" to Arm(1, 30), "trojan" to Arm(25, 3))
        val order = FitScore.order(listOf(reality, trojan)) { arms[ProtocolLearning.kindOf(it.link)] ?: Arm(0, 0) }
        assertEquals("t", order.first().identity)
    }

    @Test fun shadowReportsDisagreement() {
        val actual = listOf(cand("r", "vless://r@h:443?security=reality"), cand("t", "trojan://t@h2:443"))
        val arms = mapOf("reality" to Arm(1, 30), "trojan" to Arm(25, 3))
        val v = Shadow.compare(actual) { arms[ProtocolLearning.kindOf(it.link)] ?: Arm(0, 0) }
        assertFalse(v.agrees)
        assertEquals("t", v.shadowTop?.identity)
        assertEquals(2, v.actualRankInShadow)
        assertTrue(Shadow.compare(emptyList()) { Arm(0, 0) }.agrees.not())
    }

    @Test fun replayShowsLearningBeatsStaticRankWhenProtocolIsCut() {
        // оператор режет Reality: статический рейтинг всё равно ставит его первым, обучение это замечает
        var arm = Arm(0, 0)
        val decisions = ArrayList<Replay.Decision>()
        repeat(40) { i ->
            val list = listOf(cand("r$i", "vless://r$i@h:443?security=reality", rel = 0.6), cand("t$i", "trojan://t$i@h:443", rel = 0.55))
            decisions += Replay.Decision(list, setOf("t$i"))
        }
        val staticRate = Replay.successRate(decisions) { Ranking.rank(it).firstOrNull() }
        var learned = 0
        var armT = Arm(0, 0)
        for (d in decisions) {
            val pick = FitScore.order(d.candidates) { if (ProtocolLearning.kindOf(it.link) == "reality") arm else armT }.first()
            val ok = pick.identity in d.works
            if (ProtocolLearning.kindOf(pick.link) == "reality") arm = arm.after(ok) else armT = armT.after(ok)
            if (ok) learned++
        }
        assertEquals(0.0, staticRate, 0.0001)
        assertTrue("learned=$learned", learned >= 30)
    }
}
