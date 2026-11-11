package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3939Test {
    private val h = 3_600_000L

    @Test fun userOwnLinksAreNeverExcluded() {
        val u = "https://your-durev.com/sub/abc/white/"
        assertTrue(DefaultSources.isExcluded(u))
        assertTrue(DefaultSources.isExcluded(u, emptyList()))
        assertFalse(DefaultSources.isExcluded(u, listOf(u)))
        assertFalse(DefaultSources.isExcluded("https://example.com/x", emptyList()))
    }

    private fun sched(): SourceSchedule = SourceSchedule(File.createTempFile("sched", ".tsv").also { it.delete() })

    @Test fun learningPhaseNeverSkips() {
        val s = sched()
        s.record("u", "a", 0L)
        s.record("u", "b", 12 * h)
        s.record("u", "c", 24 * h)
        assertFalse(s.skip("u", 25 * h, true)) // всего 2 изменения — ещё учимся
    }

    @Test fun skipsOnlyWhenEarlyAndCached() {
        val s = sched()
        var t = 0L
        s.record("u", "h0", t)
        for (i in 1..4) { t += 12 * h; s.record("u", "h$i", t) }
        assertEquals(12.0, s.intervalHours("u")!!, 0.01)
        assertTrue(s.skip("u", t + 3 * h, true))
        assertFalse(s.skip("u", t + 3 * h, false)) // без кэша пропуск невозможен
        assertFalse(s.skip("u", t + 7 * h, true)) // прошла половина интервала — пора
        // каждая четвёртая — настоящая
        repeat(SourceSchedule.VERIFY_EVERY) { s.noteSkip("u") }
        assertFalse(s.skip("u", t + 3 * h, true))
        // содержимое поменялось раньше срока — счётчик пропусков сброшен, интервал пересчитан
        assertTrue(s.record("u", "new", t + 4 * h))
        assertTrue(s.skip("u", t + 5 * h, true)) // счётчик пропусков обнулён, срок считается от нового изменения
    }

    @Test fun fastSourcesNeverSkipped() {
        val s = sched()
        var t = 0L
        s.record("u", "x0", t)
        for (i in 1..6) { t += 2 * h; s.record("u", "x$i", t) }
        assertFalse(s.skip("u", t + 1 * h, true)) // обновляется чаще раза в 6 часов
    }

    @Test fun schedulePersists() {
        val f = File.createTempFile("sched", ".tsv").also { it.delete() }
        val s = SourceSchedule(f)
        s.record("https://a/b", "h1", 100L)
        s.record("https://a/b", "h2", 200L)
        s.save()
        val s2 = SourceSchedule(f)
        assertFalse(s2.record("https://a/b", "h2", 300L)) // тот же отпечаток — не изменение
        assertTrue(s2.record("https://a/b", "h3", 400L))
        f.delete()
    }

    @Test fun medianInterval() {
        assertNull(SourceSchedule.medianInterval(listOf(5L)))
        assertEquals(10L, SourceSchedule.medianInterval(listOf(0L, 10L, 20L, 30L)))
    }

    @Test fun anrSummary() {
        val trace = "\"Signal Catcher\" daemon prio=10\n  at x.y\n\n\"main\" prio=5 tid=1 Blocked\n  at java.lang.Object.wait(Native method)\n  at kotlinx.coroutines.runBlocking(Builders.kt:50)\n  at com.phoenix.app.QuickConnect.pick(QuickConnect.kt:10)\n  at android.app.Activity.performCreate(Activity.java:1)\n\n\"other\" prio=5\n"
        val t = ExitReasons.anrSummary(trace)
        assertTrue("главный поток: java.lang.Object.wait(Native method)" in t)
        assertTrue("com.phoenix.app.QuickConnect.pick" in t)
        assertTrue("main" !in "other" && "other" !in t)
        assertEquals("трасса получена, главный поток не найден", ExitReasons.anrSummary("ничего"))
    }
}
