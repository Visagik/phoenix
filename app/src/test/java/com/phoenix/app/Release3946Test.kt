package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3946Test {
    @Test fun fetchAndTargetsAreCountedAndSaved() {
        val d = java.nio.file.Files.createTempDirectory("enr46").toFile()
        val f = File(d, "e.tsv")
        val m = EnergyMeter(f)
        m.add("fetch", 17, "2026-11-05", 1_000_000L)
        m.add("targets", 30, "2026-11-05", 1_000_001L)
        m.save(9_000_000L)
        val again = EnergyMeter(f).day("2026-11-05")
        assertEquals(17L, again.fetch); assertEquals(30L, again.targets)
        assertEquals(17 * EnergyMeter.FETCH_BYTES + 30 * EnergyMeter.TARGETS_BYTES, again.bytes)
        val t = EnergyMeter(f).text("2026-11-05")
        assertTrue("скачано источников 17" in t && "проверок сервисов целей 30" in t)
    }

    @Test fun oldFiveColumnFileStillLoads() {
        val d = java.nio.file.Files.createTempDirectory("enr46b").toFile()
        val f = File(d, "e.tsv")
        f.writeText("2026-11-01\t3\t1\t100\t5000\n")
        val day = EnergyMeter(f).day("2026-11-01")
        assertEquals(3L, day.health); assertEquals(0L, day.fetch); assertEquals(5000L, day.bytes)
    }

    @Test fun writesAreBatched() {
        val d = java.nio.file.Files.createTempDirectory("enr46c").toFile()
        val f = File(d, "e.tsv")
        val m = EnergyMeter(f)
        m.add("health", 1, "2026-11-05", EnergyMeter.SAVE_EVERY_MS + 1) // первая запись сбрасывает
        val first = f.lastModified()
        m.add("health", 1, "2026-11-05", EnergyMeter.SAVE_EVERY_MS + 2)
        assertEquals(1L, EnergyMeter(f).day("2026-11-05").health) // вторая ещё только в памяти
        assertTrue(first > 0)
    }
}
