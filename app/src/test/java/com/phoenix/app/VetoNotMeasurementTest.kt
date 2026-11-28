package com.phoenix.app

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * О3 (T5): пометка «Не работает» ставит пользователь, это не замер. Результат остаётся FAILED (значок ✗, выбор сервера под цель),
 * но в историю шансов она не идёт: иначе каждая перепроверка добавляла бы «неудачу» от одного нажатия.
 */
class VetoNotMeasurementTest {
    private val link = "trojan://pw@veto.example.com:443#V"
    private val key = CheckStore.keyOf(link)

    // история шансов лежит рядом с файлом проверок, поэтому у каждого теста своя папка
    private fun store() = CheckStore(File(java.nio.file.Files.createTempDirectory("veto").toFile().also { it.deleteOnExit() }, "checks.tsv"))

    @Test fun vetoedServiceIsNotWrittenToTheOddsHistoryButOthersAre() {
        val st = store()
        st.putAll(mapOf(key to CheckResult("2026-11-26", 50, mapOf("X" to CheckServices.FAILED, "Y" to 120L), vetoed = setOf("X"))))
        assertNull(st.odds()(key, "X"))
        assertNotNull(st.odds()(key, "Y"))
    }

    @Test fun vetoedResultStaysFailedInChecks() {
        val st = store()
        st.putAll(mapOf(key to CheckResult("2026-11-26", 50, mapOf("X" to CheckServices.FAILED, "Y" to 120L), vetoed = setOf("X"))))
        assertEquals(CheckServices.FAILED, st.load().getValue(key).services.getValue("X"))
    }

    @Test fun regionClosedAndMarkedStillCountsAsRealMeasurement() {
        val st = store()
        // сервис закрыт по региону (настоящий замер) и заодно помечен: vetoed пуст, событие пишется
        st.putAll(mapOf(key to CheckResult("2026-11-26", 50, mapOf("X" to CheckServices.FAILED), vetoed = emptySet())))
        assertNotNull(st.odds()(key, "X"))
    }

    @Test fun checkerReportsOnlyServicesFailedBecauseOfTheMark() {
        val svc = CheckServices.ALL.first { it.id == "YT" }
        val other = CheckServices.ALL.first { it.id == "TG" }
        val checker = ServiceChecker(
            probe = { _, _ -> 50L },
            veto = { _, url -> url == svc.url || url == other.url },
            exit = { "RU" },
            region = { _, _ -> null },
        )
        val r = runBlocking { checker.check(listOf(link), listOf(svc, other), "2026-11-26") }.getValue(key)
        assertEquals(CheckServices.FAILED, r.services.getValue(svc.id))
        assertTrue(svc.id in r.vetoed)
    }

    @Test fun markedServiceThatDidNotOpenIsAnOrdinaryFailure() {
        val svc = CheckServices.ALL.first { it.id == "YT" }
        val checker = ServiceChecker(probe = { _: String, u: String -> if (u == svc.url) -1L else 50L }, veto = { _, url -> url == svc.url })
        val r = runBlocking { checker.check(listOf(link), listOf(svc), "2026-11-26") }.getValue(key)
        assertEquals(CheckServices.FAILED, r.services.getValue(svc.id))
        assertTrue(r.vetoed.isEmpty()) // не открылся сам: это настоящий замер
    }

    @Test fun markedServiceThatIsClosedByRegionIsARealMeasurement() {
        val svc = CheckServices.ALL.first { it.id == "CL" }
        val checker = ServiceChecker(probe = { _, _ -> 50L }, veto = { _, url -> url == svc.url }, exit = { "RU" })
        val r = runBlocking { checker.check(listOf(link), listOf(svc), "2026-11-26") }.getValue(key)
        assertEquals(CheckServices.FAILED, r.services.getValue(svc.id))
        assertTrue(r.vetoed.isEmpty()) // закрыт по стране выхода: настоящий замер, событие пишется
    }
}
