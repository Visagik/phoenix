package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class Release3936Test {
    private fun tmp(): File = File.createTempFile("stat", ".tsv").also { it.delete(); it.deleteOnExit() }

    @Test fun statsAccumulateAndSurviveReload() {
        val f = tmp()
        val s = UsageStats(f)
        s.addConnected("Server A", 600, "2026-10-29", 0L)
        s.addConnected("Server A", 300, "2026-10-29", 0L)
        s.addConnected("Server B", 60, "2026-10-28", 0L)
        s.add(UsageStats.SWITCHES, 1, "2026-10-29", 0L)
        s.add(UsageStats.SESSIONS, 1, "2026-10-29", 0L)
        s.save(1L)
        val s2 = UsageStats(f)
        val d = s2.lastDays(LocalDate.parse("2026-10-29"), 3)
        assertEquals(900L, d[0].get(UsageStats.CONNECTED))
        assertEquals(1L, d[0].get(UsageStats.SWITCHES))
        assertEquals(60L, d[1].get(UsageStats.CONNECTED))
        assertTrue(d[2].values.isEmpty())
        assertEquals(900L, d[0].get(UsageStats.serverKey("Server A")))
    }

    @Test fun statsIgnoreNonPositiveAndKeepOnlyTopServers() {
        val f = tmp()
        val s = UsageStats(f)
        s.add(UsageStats.SWITCHES, 0, "2026-10-29", 0L)
        s.add(UsageStats.SWITCHES, -5, "2026-10-29", 0L)
        for (i in 1..40) s.addConnected("srv$i", i.toLong(), "2026-10-29", 0L)
        s.save(1L)
        val d = UsageStats(f).lastDays(LocalDate.parse("2026-10-29"), 1)[0]
        assertEquals(0L, d.get(UsageStats.SWITCHES))
        assertEquals(UsageStats.MAX_SERVERS_PER_DAY, d.values.keys.count { it.startsWith(UsageStats.SERVER_PREFIX) })
        assertTrue(d.values.containsKey(UsageStats.serverKey("srv40")))
        assertFalse(d.values.containsKey(UsageStats.serverKey("srv1")))
    }

    @Test fun oldDaysAreDropped() {
        val f = tmp()
        val s = UsageStats(f)
        s.add(UsageStats.SESSIONS, 1, "2026-08-01", 0L)
        s.add(UsageStats.SESSIONS, 1, "2026-10-29", 0L)
        s.save(1L)
        val s2 = UsageStats(f)
        assertTrue(s2.lastDays(LocalDate.parse("2026-08-01"), 1)[0].values.isEmpty())
        assertEquals(1L, s2.lastDays(LocalDate.parse("2026-10-29"), 1)[0].get(UsageStats.SESSIONS))
    }

    @Test fun statsText() {
        assertEquals("45 с", StatsText.duration(45))
        assertEquals("5 мин", StatsText.duration(300))
        assertEquals("2 ч 5 мин", StatsText.duration(7500))
        val empty = StatsText.lines(listOf(UsageStats.Day("2026-10-29", emptyMap())))
        assertTrue(empty[0].startsWith("Пока нет данных"))
        val days = listOf(
            UsageStats.Day("2026-10-29", mapOf(UsageStats.CONNECTED to 3600L, UsageStats.SWITCHES to 2L, UsageStats.SESSIONS to 1L, UsageStats.serverKey("A") to 3000L, UsageStats.serverKey("B") to 600L)),
            UsageStats.Day("2026-10-28", mapOf(UsageStats.CONNECTED to 60L, UsageStats.FAILURES to 1L)),
        )
        val t = StatsText.lines(days)
        assertTrue(t.any { it.startsWith("Сегодня: VPN 1 ч 0 мин") && "смен сервера 2" in it })
        assertTrue(t.any { it.startsWith("Вчера:") && "обрывов 1" in it })
        assertTrue(t.any { it.startsWith("За 2 дн.") })
        assertTrue(t.indexOfFirst { "A — " in it } < t.indexOfFirst { "B — " in it })
    }

    @Test fun killBanner() {
        assertTrue(KillBanner.systemKill(3))
        assertTrue(KillBanner.systemKill(14))
        assertFalse(KillBanner.systemKill(10))
        assertFalse(KillBanner.systemKill(16))
        assertTrue(KillBanner.visible(1000L, 0L, 5000L))
        assertFalse(KillBanner.visible(1000L, 1000L, 5000L))
        assertFalse(KillBanner.visible(0L, 0L, 5000L))
        assertFalse(KillBanner.visible(1000L, 0L, 1000L + KillBanner.SHOW_MS + 1))
        val t = KillBanner.text(3, 2 * 3_600_000L, "Включите автозапуск.")
        assertTrue("2 ч назад" in t && "не хватило памяти" in t && t.endsWith("Включите автозапуск."))
    }

    @Test fun sharedTextYieldsSourceLinks() {
        val l = UserSources.parseSourceList("Подписка:\nhttps://example.com/sub.txt\nещё http://a.org/x и текст")
        assertEquals(listOf("https://example.com/sub.txt", "http://a.org/x"), l)
        assertTrue(UserSources.parseSourceList("просто текст без ссылок").isEmpty())
    }
}
