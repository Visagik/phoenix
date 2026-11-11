package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SearchTest {
    private fun link(i: Int) = "trojan://pw@10.0.${i / 250}.${i % 250 + 1}:443#Server number $i"
    private fun cand(i: Int) = Candidate(link(i), ConfigIdentity.key(link(i)), 0, -1.0, 0)
    private fun indexOf(config: String): Int {
        val m = Regex("""\"address\":\"10\.0\.(\d+)\.(\d+)\"""").find(config)!!
        return m.groupValues[1].toInt() * 250 + m.groupValues[2].toInt() - 1
    }

    @Test
    fun findsTheFewWorkingAmongMany() {
        val tcpCalls = AtomicInteger()
        val probeCalls = AtomicInteger()
        val s = ServerSearch(
            reach = { h, _ -> tcpCalls.incrementAndGet(); if (h.substringAfterLast('.').toInt() % 10 == 1) h else null },
            probe = { cfg, _ -> probeCalls.incrementAndGet(); if (indexOf(cfg) % 20 == 0) 120L + indexOf(cfg) else -1L },
        )
        val results = ConcurrentHashMap<Int, Long>()
        val found = runBlocking { s.find((0 until 400).map(::cand), want = 3, budgetMs = 10_000) { c, ms -> results[indexOf(XrayConfig.probeConfig(c.link))] = ms } }
        assertEquals(3, found.size)
        assertTrue(found.all { indexOf(XrayConfig.probeConfig(it.cand.link)) % 20 == 0 })
        assertEquals(found.map { it.ms }.sorted(), found.map { it.ms }) // быстрейшие первыми
        assertTrue("поиск должен остановиться, не дожидаясь конца очереди", tcpCalls.get() < 400)
    }

    @Test
    fun deadServersNeverStartTheCore() {
        val probed = ConcurrentHashMap.newKeySet<Int>()
        val s = ServerSearch(
            reach = { h, _ -> if (h.substringAfterLast('.').toInt() <= 100) h else null },
            probe = { cfg, _ -> probed += indexOf(cfg); 50L },
        )
        val codes = ConcurrentHashMap<Int, Long>()
        runBlocking { s.find((0 until 300).map(::cand), want = 1000, budgetMs = 10_000) { c, ms -> codes[c.link.hashCode()] = ms } }
        assertTrue(probed.all { (it % 250 + 1) <= 100 })
        assertTrue(codes.values.any { it == ServerSearch.TCP_FAIL })
    }

    @Test
    fun sameHostIsTestedOnce() {
        val calls = AtomicInteger()
        val a = Candidate("trojan://pw@1.2.3.4:443#Name one", "k1", 0, -1.0, 0)
        val b = Candidate("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:8443?security=tls#Name one", "k2", 0, -1.0, 0)
        val s = ServerSearch(reach = { h, _ -> h }, probe = { _, _ -> calls.incrementAndGet(); 10L })
        val found = runBlocking { s.find(listOf(a, b), want = 5, budgetMs = 5_000) }
        assertEquals(1, found.size)
        assertEquals(1, calls.get())
    }

    @Test
    fun budgetEndsASearchThatIsTooSlow() {
        val s = ServerSearch(reach = { h, _ -> h }, probe = { _, _ -> Thread.sleep(2_000); 10L }, probeParallel = 2)
        val t0 = System.currentTimeMillis()
        val found = runBlocking { s.find((0 until 20).map(::cand), want = 1, budgetMs = 300) }
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
        assertTrue(found.isEmpty())
    }

    @Test
    fun nothingToSearch() {
        val s = ServerSearch(reach = { h, _ -> h }, probe = { _, _ -> 1L })
        assertTrue(runBlocking { s.find(emptyList(), 1, 100) }.isEmpty())
    }

    // ---- порядок: живые первыми, чужие по названию — последними ----

    @Test
    fun aliveFirstMismatchedLast() {
        val rows = listOf(
            ConfigRow("trojan://pw@1.1.1.1:443#Germany 52 [📃 Белые списки]", "trojan", "Germany%2052%20%5B%F0%9F%93%83%20%D0%91%D0%B5%D0%BB%D1%8B%D0%B5%20%D1%81%D0%BF%D0%B8%D1%81%D0%BA%D0%B8%5D", "DE", null, 5),
            ConfigRow("trojan://pw@2.2.2.2:443#Unknown Server", "trojan", "Unknown Server", "NL", null, 5),
            ConfigRow("trojan://pw@3.3.3.3:443#Alive Server", "trojan", "Alive Server", "FI", null, 0),
        )
        val today = LocalDate.now().toString()
        val checks = mapOf(CheckStore.keyOf(rows[2].raw) to CheckResult(today, 150, emptyMap(), host = "3.3.3.3:443"))
        val ranked = Ranking.rank(Ranking.candidates(rows, checks, LocalDate.now(), null, Mode.BLACKLIST))
        assertEquals("Alive Server", rows.first { it.raw == ranked[0].link }.name) // проверенный живой впереди, даже без «стабильности»
        assertTrue(ranked.last().mismatch)
        assertTrue(rows.first { it.raw == ranked.last().link }.displayName.contains("Белые списки"))
        // в режиме белых списков эта метка не мешает
        val wl = Ranking.rank(Ranking.candidates(rows, checks, LocalDate.now(), null, Mode.WHITELIST))
        assertFalse(wl.any { it.mismatch })
        // без режима ничего не меняется
        assertFalse(Ranking.candidates(rows, checks).any { it.mismatch })
    }

    @Test
    fun staleOrDeadChecksAreNotAlive() {
        val rows = listOf(ConfigRow("trojan://pw@3.3.3.3:443#Alive Server", "trojan", "Alive Server", "FI", null, 0))
        val old = mapOf(CheckStore.keyOf(rows[0].raw) to CheckResult(LocalDate.now().minusDays(3).toString(), 150, emptyMap(), host = "3.3.3.3:443"))
        assertFalse(Ranking.candidates(rows, old).single().alive)
        val moved = mapOf(CheckStore.keyOf(rows[0].raw) to CheckResult(LocalDate.now().toString(), 150, emptyMap(), host = "9.9.9.9:443"))
        assertFalse(Ranking.candidates(rows, moved).single().alive) // адрес сменился — проверка устарела
    }

    @Test
    fun listHints() {
        assertEquals(Mode.WHITELIST, ListHint.of("Germany 52 🇩🇪 → [📃 Белые списки]"))
        assertEquals(Mode.WHITELIST, ListHint.of("RU whitelist #3"))
        assertEquals(Mode.BLACKLIST, ListHint.of("Чёрные списки 1"))
        assertNull(ListHint.of("Auto 4 → [🚀 Оптимальная]"))
        assertNull(ListHint.of("Белые и чёрные списки")) // двусмысленно — не гадаем
    }
}
