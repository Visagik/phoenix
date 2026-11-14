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
        assertEquals(3L, day.health); assertEquals(0L, day.fetch); assertEquals(5000L, day.bytes); assertEquals(0L, day.diag)
    }

    @Test fun diagIsCountedAndSaved() {
        // раньше DIAG (Gemini/NotebookLM) расходовал сеть каждые 2 минуты, но нигде не учитывался (слепая зона EnergyMeter)
        val d = java.nio.file.Files.createTempDirectory("enr46d").toFile()
        val f = File(d, "e.tsv")
        val m = EnergyMeter(f)
        m.add("diag", 4, "2026-11-05", 1_000_000L)
        m.save(9_000_000L)
        val again = EnergyMeter(f).day("2026-11-05")
        assertEquals(4L, again.diag)
        assertEquals(4 * EnergyMeter.DIAG_BYTES, again.bytes)
        assertTrue("проверок Google (Gemini/NotebookLM) 4" in EnergyMeter(f).text("2026-11-05"))
        // старый 7-колоночный файл (без diag) без правок всё ещё читается как раньше
        val old7cols = File(d, "old.tsv")
        old7cols.writeText("2026-11-02\t3\t1\t0\t5000\t2\t7\n")
        val oldDay = EnergyMeter(old7cols).day("2026-11-02")
        assertEquals(7L, oldDay.targets); assertEquals(0L, oldDay.diag)
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
