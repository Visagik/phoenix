package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class Release3930Test {
    @Test fun healthRequires204ForGenerate204() {
        assertTrue(HealthVerdict.ok("http://cp.cloudflare.com/generate_204", 204))
        assertFalse(HealthVerdict.ok("http://cp.cloudflare.com/generate_204", 200))
        assertFalse(HealthVerdict.ok("http://connectivitycheck.gstatic.com/generate_204", 302))
        assertTrue(HealthVerdict.ok("https://example.com/", 200))
        assertFalse(HealthVerdict.ok("https://example.com/", 503))
    }

    @Test fun shapingNeedsHistoryThenDrop() {
        val d = ShapingDetector()
        repeat(5) { assertFalse(d.feed(6.0)) }
        assertFalse(d.feed(5.5)) // мало данных до падения не мешает: падения ещё нет
        assertFalse(d.feed(5.0))
        val d2 = ShapingDetector()
        repeat(6) { d2.feed(6.0) }
        assertFalse(d2.feed(1.0)) // один низкий замер — ещё не подозрение
        assertTrue(d2.feed(1.2))
        assertTrue(d2.peak > 5.0)
    }

    @Test fun shapingIgnoresLowTrafficAndSlowStart() {
        val d = ShapingDetector()
        repeat(8) { assertFalse(d.feed(1.0)) } // и раньше было медленно — это не шейпинг
        val e = ShapingDetector()
        e.feed(6.0); e.feed(6.0)
        assertFalse(e.feed(1.0)); assertFalse(e.feed(1.0)) // мало «хороших» замеров
    }

    @Test fun shapingResetClears() {
        val d = ShapingDetector()
        repeat(6) { d.feed(6.0) }
        d.reset()
        assertFalse(d.feed(1.0))
        assertEquals(0.0, d.peak, 0.0)
    }

    @Test fun slowMonitorCanSwitchRespectsCooldown() {
        val m = SlowMonitor(0.5)
        assertTrue(m.canSwitch(1_000_000L))
        m.switched(1_000_000L)
        assertFalse(m.canSwitch(1_000_000L + 60_000L))
        assertTrue(m.canSwitch(1_000_000L + 6 * 60_000L))
    }

    @Test fun focusRule() {
        assertTrue(FocusRule.active("com.bank", setOf("com.bank", "com.game")))
        assertFalse(FocusRule.active("com.other", setOf("com.bank")))
        assertFalse(FocusRule.active(null, setOf("com.bank")))
        assertFalse(FocusRule.active("com.bank", setOf("com.bank"), enabled = false))
    }

    @Test fun appHoursFindsAppsOpenedAtThisHour() {
        val zone = ZoneId.of("UTC")
        val dir = File.createTempFile("apph", "").also { it.delete(); it.mkdirs() }
        val h = AppHours(dir, zone)
        // 10 понедельников подряд: приложение открывали в 09:00
        val monday = ZonedDateTime.of(2026, 8, 3, 9, 30, 0, 0, zone)
        for (w in 0 until 10) h.record("com.bank", monday.plusWeeks(w.toLong()).toInstant().toEpochMilli())
        // ещё и в будни у другого приложения 08:00
        for (d in 0 until 10) h.record("com.other", monday.plusDays(d.toLong()).withHour(15).toInstant().toEpochMilli())
        val now = ZonedDateTime.of(2026, 10, 12, 8, 50, 0, 0, zone).toInstant().toEpochMilli() // понедельник 08:50
        assertEquals(listOf("com.bank"), h.soon(listOf("com.bank", "com.other"), now))
    }
}
