package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3932Test {
    @Test fun smartVerdictNoNetworkOrInternetDoesNotSwitch() {
        val a = SmartVerdict.decide(networkNone = true, directOffline = false, tunnelWorks = false, app = "Telegram")
        assertEquals(SmartVerdict.Kind.NO_NETWORK, a.kind)
        assertFalse(a.switchServer)
        val b = SmartVerdict.decide(false, true, false, "")
        assertEquals(SmartVerdict.Kind.NO_INTERNET, b.kind)
        assertFalse(b.switchServer)
    }

    @Test fun smartVerdictSwitchesWhenServerDeadOrAsked() {
        val dead = SmartVerdict.decide(false, false, false, "Telegram")
        assertEquals(SmartVerdict.Kind.SERVER_DEAD, dead.kind)
        assertTrue(dead.switchServer && "«Telegram»" in dead.text)
        val ok = SmartVerdict.decide(false, false, true, "")
        assertEquals(SmartVerdict.Kind.TUNNEL_OK, ok.kind)
        assertTrue(ok.switchServer && "«" !in ok.text)
    }

    @Test fun singleFlightSkipsDuplicates() {
        val f = SingleFlight()
        assertTrue(f.tryEnter("health"))
        assertFalse(f.tryEnter("health"))
        assertTrue(f.tryEnter("speed")) // другой ключ не мешает
        f.leave("health")
        assertTrue(f.tryEnter("health"))
        var ran = 0
        val g = SingleFlight()
        g.once("k", { -1 }) { ran++; g.once("k", { ran += 10; -1 }) { 0 } }
        assertEquals(11, ran) // вложенный вызов с тем же ключом пропущен
        assertFalse(g.isRunning("k")) // ключ освобождён
    }

    @Test fun singleFlightReleasesOnException() {
        val f = SingleFlight()
        try { f.once("k", { 0 }) { throw IllegalStateException("x") } } catch (_: IllegalStateException) {}
        assertTrue(f.tryEnter("k"))
    }

    @Test fun energyMeterCountsAndPersists() {
        val d = java.nio.file.Files.createTempDirectory("enrg").toFile()
        val file = File(d, "energy.tsv")
        val m = EnergyMeter(file)
        m.add("health", 3, "2026-10-27", 1_000_000L)
        m.add("speed", 1, "2026-10-27", 1_000_001L)
        m.add("search", 100, "2026-10-27", 1_000_002L)
        m.add("bogus", 5, "2026-10-27", 1_000_003L)
        val day = m.day("2026-10-27")
        assertEquals(3L, day.health); assertEquals(1L, day.speed); assertEquals(100L, day.search)
        assertEquals(3 * EnergyMeter.HEALTH_BYTES + EnergyMeter.SPEED_BYTES + 100 * EnergyMeter.SEARCH_BYTES, day.bytes)
        m.save(2_000_000L)
        val again = EnergyMeter(file)
        assertEquals(day, again.day("2026-10-27"))
        assertEquals("ничего не делалось", again.text("2026-10-26"))
        assertTrue("проверок туннеля 3" in again.text("2026-10-27"))
    }

    @Test fun energyMeterKeepsFewDays() {
        val d = java.nio.file.Files.createTempDirectory("enrg").toFile()
        val m = EnergyMeter(File(d, "e.tsv"))
        for (i in 1..20) m.add("health", 1, "2026-10-%02d".format(i), i.toLong())
        m.save(10_000_000L)
        val lines = File(d, "e.tsv").readLines()
        assertEquals(EnergyMeter.KEEP_DAYS, lines.size)
        assertTrue(lines.first().startsWith("2026-10-07")) // самые старые отброшены
    }

    @Test fun mbFormat() {
        assertEquals("500 КБ", EnergyMeter.mb(512_000))
        assertEquals("2.0 МБ", EnergyMeter.mb(2 * 1048576L))
    }
}
