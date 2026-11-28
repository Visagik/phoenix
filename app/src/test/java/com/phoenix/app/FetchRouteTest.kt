package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchRouteTest {
    private companion object { const val FALLBACK = "https://www.gstatic.com/generate_204" }

    /** Повторяет порядок действий Collector.fetchWithRetry для одного источника (без сети): те же вызовы FetchRoute и FetchPolicy. */
    private class Sim(val route: FetchRoute = FetchRoute(), val vpnOn: Boolean = true, val offline: Boolean = false) {
        val orders = ArrayList<FetchRoute.Order>()
        val notes = ArrayList<String>()
        var vpnAttempts = 0
        var directAttempts = 0
        /** Ответ контрольной проверки VPN (по умолчанию VPN мёртв, как в прежних тестах). */
        var controlAlive = false
        val controlTargets = ArrayList<String>()

        private fun control() {
            route.takeControl(FALLBACK)?.let { controlTargets += it; route.controlResult(controlAlive) }
        }

        /** true — источник скачан. [bytes] — сколько байт пришло через VPN до ошибки, [host] — «схема://узел/» источника. */
        fun fetch(directOk: Boolean, vpnOk: Boolean, vpnCode: Int = if (vpnOk) 200 else 0, bytes: Long = 0, host: String = "https://h/"): Boolean {
            val order = route.order(vpnOn && !offline)
            orders += order
            var vpnFirstFailed = false
            if (order == FetchRoute.Order.VPN_THEN_DIRECT) {
                vpnAttempts++
                route.vpnResult(vpnOk, vpnCode, afterDirectFail = false, bytes = bytes, origin = host)
                control()
                if (vpnOk) return true
                vpnFirstFailed = true
            }
            directAttempts++
            if (directOk) {
                if (!vpnFirstFailed) route.directOpened()
                return true
            }
            if (vpnFirstFailed) { notes += FetchPolicy.describe(0, 0, null); return false }
            if (order == FetchRoute.Order.DIRECT_THEN_VPN) {
                vpnAttempts++
                route.vpnResult(vpnOk, vpnCode, afterDirectFail = true, bytes = bytes, origin = host)
                control()
                if (vpnOk) return true
                notes += FetchPolicy.describe(0, 0, null)
            } else notes += FetchPolicy.describe(0, null, route.noVpnReason(vpnOn, offline))
            return false
        }
    }

    @Test fun whitelistRunDownloadsAllThroughVpnFromFourth() {
        val sim = Sim()
        val got = (1..29).count { sim.fetch(directOk = false, vpnOk = true) }
        assertEquals(29, got)
        assertEquals(FetchRoute.Order.DIRECT_THEN_VPN, sim.orders[2])
        assertEquals(FetchRoute.Order.VPN_THEN_DIRECT, sim.orders[3])
        assertTrue(sim.orders.drop(3).all { it == FetchRoute.Order.VPN_THEN_DIRECT })
        assertTrue(sim.notes.none { it.contains("VPN выключен") })
        // с четвёртого источника прямую попытку (30 с таймаута) не делаем вообще
        assertEquals(3, sim.directAttempts)
        assertEquals(29, sim.vpnAttempts)
        assertTrue(sim.route.summary()!!.contains("через VPN сразу: 26 попыток, скачано 26; после прямой попытки: 3 попыток, скачано 3"))
    }

    @Test fun deadVpnStopsAfterThreeAndSaysWhy() {
        val sim = Sim()
        val got = (1..10).count { sim.fetch(directOk = false, vpnOk = false) }
        assertEquals(0, got)
        assertEquals(3, sim.vpnAttempts)
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, sim.route.mode())
        assertTrue(sim.orders.drop(3).all { it == FetchRoute.Order.DIRECT_ONLY })
        assertTrue(sim.notes[3].contains("путь через VPN в этом сборе не отвечает"))
        assertFalse(sim.notes[3].contains("VPN выключен"))
        assertTrue(sim.route.summary()!!.contains("только напрямую"))
    }

    @Test fun blacklistRunNeverTouchesVpn() {
        val sim = Sim()
        assertEquals(20, (1..20).count { sim.fetch(directOk = true, vpnOk = true) })
        assertEquals(0, sim.vpnAttempts)
        assertEquals(FetchRoute.Mode.DIRECT_FIRST, sim.route.mode())
        assertNull(sim.route.summary())
    }

    @Test fun directOnlySourceStillDownloadsInVpnFirstMode() {
        val sim = Sim()
        repeat(3) { assertTrue(sim.fetch(directOk = false, vpnOk = true)) }
        assertEquals(FetchRoute.Mode.VPN_FIRST, sim.route.mode())
        // зеркало, которое через VPN не открылось, но открывается напрямую
        assertTrue(sim.fetch(directOk = true, vpnOk = false))
        assertEquals(FetchRoute.Order.VPN_THEN_DIRECT, sim.orders.last())
        assertEquals(FetchRoute.Mode.VPN_FIRST, sim.route.mode())
        assertTrue(sim.fetch(directOk = false, vpnOk = true))
    }

    @Test fun directSuccessResetsRescuedSeries() {
        val r = FetchRoute()
        repeat(2) { r.vpnResult(true, 200, afterDirectFail = true) }
        r.directOpened()
        repeat(2) { r.vpnResult(true, 200, afterDirectFail = true) }
        assertEquals(FetchRoute.Mode.DIRECT_FIRST, r.mode())
        r.vpnResult(true, 200, afterDirectFail = true)
        assertEquals(FetchRoute.Mode.VPN_FIRST, r.mode())
    }

    @Test fun vpnSuccessResetsFailureSeries() {
        val r = FetchRoute()
        repeat(2) { r.vpnResult(false, 0, afterDirectFail = true) }
        r.vpnResult(true, 200, afterDirectFail = true)
        repeat(2) { r.vpnResult(false, 0, afterDirectFail = true) }
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
    }

    @Test fun reasonIsTrueNotDefault() {
        val r = FetchRoute()
        assertEquals("VPN выключен", r.noVpnReason(vpnOn = false, offline = false))
        assertEquals("нет сети", r.noVpnReason(vpnOn = true, offline = true))
        assertNull(r.noVpnReason(vpnOn = true, offline = false))
        repeat(3) { r.vpnResult(false, 0, afterDirectFail = true) }
        assertNull(r.noVpnReason(vpnOn = true, offline = false)) // до контрольной проверки путь ещё не закрыт
        assertNotNull(r.takeControl(FALLBACK))
        r.controlResult(false)
        assertEquals("путь через VPN в этом сборе не отвечает", r.noVpnReason(vpnOn = true, offline = false))
    }

    @Test fun describeNeverSaysVpnOffWhenVpnIsOn() {
        assertEquals("напрямую: нет ответа", FetchPolicy.describe(0, null, null))
        assertEquals("напрямую: нет ответа; через VPN: нет ответа", FetchPolicy.describe(0, 0, null))
        assertEquals("напрямую: код 403; через VPN: код 200", FetchPolicy.describe(403, 200, null))
        assertEquals("напрямую: нет ответа; нет сети — через него не пробовал", FetchPolicy.describe(0, null, FetchRoute.REASON_OFFLINE))
    }

    @Test fun offlineMeansDirectOnlyOrder() {
        assertEquals(FetchRoute.Order.DIRECT_ONLY, FetchRoute().order(vpnUsable = false))
    }

    @Test fun httpAnswerThroughVpnProvesTunnelWorks() {
        val sim = Sim()
        // три удалённых зеркала подряд: напрямую обрыв, через VPN ответ 404
        repeat(3) { assertFalse(sim.fetch(directOk = false, vpnOk = false, vpnCode = 404)) }
        assertTrue(sim.route.mode() != FetchRoute.Mode.DIRECT_ONLY)
        assertFalse(sim.route.summary()!!.contains("только напрямую"))
        // следующий источник по-прежнему идёт через VPN после прямой попытки
        assertTrue(sim.fetch(directOk = false, vpnOk = true))
        assertEquals(FetchRoute.Order.DIRECT_THEN_VPN, sim.orders[3])
    }

    @Test fun threeNoConnectionsThroughVpnStopTheTunnelPath() {
        val sim = Sim()
        repeat(3) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) }
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, sim.route.mode())
    }

    @Test fun httpAnswerBreaksTheFailureSeries() {
        val sim = Sim()
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 404)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        assertTrue(sim.route.mode() != FetchRoute.Mode.DIRECT_ONLY)
        assertEquals(FetchRoute.Order.DIRECT_THEN_VPN, sim.route.order(vpnUsable = true))
    }

    // --- контрольная проверка VPN (журнал 24.11 20:40: плохие источники принимались за смерть туннеля) ---

    @Test fun threeZerosWithLiveControlDoNotStopVpn() {
        val sim = Sim().apply { controlAlive = true }
        repeat(3) { assertFalse(sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)) }
        assertEquals(1, sim.controlTargets.size)
        assertTrue(sim.route.mode() != FetchRoute.Mode.DIRECT_ONLY)
        assertFalse(sim.route.summary()!!.contains("только напрямую"))
        assertTrue(sim.route.summary()!!.contains("контрольных проверок VPN: 1, ответили 1"))
        // следующий источник по-прежнему пробуется через VPN
        assertTrue(sim.fetch(directOk = false, vpnOk = true))
        assertEquals(FetchRoute.Order.DIRECT_THEN_VPN, sim.orders[3])
    }

    @Test fun threeZerosWithDeadControlStopVpn() {
        val sim = Sim().apply { controlAlive = false }
        repeat(3) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) }
        assertEquals(1, sim.controlTargets.size)
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, sim.route.mode())
        assertEquals(FetchRoute.Order.DIRECT_ONLY, sim.route.order(vpnUsable = true))
        assertTrue(sim.route.summary()!!.contains("только напрямую"))
        // больше контрольных проверок нет: путь уже закрыт
        repeat(5) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) }
        assertEquals(1, sim.controlTargets.size)
    }

    @Test fun zeroWithBytesIsNotAVpnFailure() {
        val sim = Sim()
        // AvenCores 26.txt: 1,3 МБ пришли и оборвались — путь работает, плох источник
        repeat(10) { assertFalse(sim.fetch(directOk = false, vpnOk = false, vpnCode = 0, bytes = 1_300_000)) }
        assertTrue(sim.controlTargets.isEmpty())
        assertTrue(sim.route.mode() != FetchRoute.Mode.DIRECT_ONLY)
        assertEquals(10, sim.vpnAttempts)
    }

    @Test fun zeroWithBytesBreaksTheSeries() {
        val sim = Sim()
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0, bytes = 812)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        assertTrue(sim.controlTargets.isEmpty())
        assertTrue(sim.route.mode() != FetchRoute.Mode.DIRECT_ONLY)
    }

    @Test fun controlGoesToHostThatAlreadyAnsweredThroughVpn() {
        val sim = Sim().apply { controlAlive = true }
        sim.fetch(directOk = false, vpnOk = true, host = "https://good.example/")
        repeat(3) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0, host = "https://bad.example/") }
        assertEquals(listOf("https://good.example/"), sim.controlTargets)
    }

    @Test fun controlFallsBackToConnectivityAddressWhenNobodyAnswered() {
        val sim = Sim().apply { controlAlive = true }
        repeat(3) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0, host = "https://bad.example/") }
        assertEquals(listOf(FALLBACK), sim.controlTargets)
    }

    @Test fun oneControlPerThreeFailures() {
        val sim = Sim().apply { controlAlive = true }
        repeat(2) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) }
        assertTrue(sim.controlTargets.isEmpty())
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        assertEquals(1, sim.controlTargets.size)
        repeat(2) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) }
        assertEquals(1, sim.controlTargets.size)
        sim.fetch(directOk = false, vpnOk = false, vpnCode = 0)
        assertEquals(2, sim.controlTargets.size)
    }

    @Test fun atMostFiveControlsPerCollect() {
        val sim = Sim().apply { controlAlive = true }
        repeat(30) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) }
        assertEquals(FetchRoute.MAX_CONTROLS, sim.controlTargets.size)
        // лимит исчерпан, последняя проверка «жив»; но Д3: ещё 6 пустых неудач подряд (VPN_DEAD_AFTER × 2) — дальше только напрямую, без новых запросов
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, sim.route.mode())
        assertEquals(FetchRoute.MAX_CONTROLS * FetchRoute.VPN_DEAD_AFTER + FetchRoute.VPN_DEAD_AFTER * 2, sim.vpnAttempts)
        assertTrue(sim.route.summary()!!.contains("после исчерпанного лимита"))
    }

    @Test fun parallelLoadsMakeOnlyOneControlAtATime() {
        val r = FetchRoute()
        repeat(3) { r.vpnResult(false, 0, afterDirectFail = true) }
        assertEquals(FALLBACK, r.takeControl(FALLBACK))
        // пока первая проверка идёт, остальные потоки новую не начинают и режим не меняют
        r.vpnResult(false, 0, afterDirectFail = true)
        assertNull(r.takeControl(FALLBACK))
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
        r.controlResult(true)
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
    }

    // --- причина кода 0 в журнале ---

    @Test fun failureKindsAreNamed() {
        assertEquals(FetchFailure.Kind.READ_TIMEOUT, FetchFailure.classify(java.net.SocketTimeoutException("Read timed out")))
        assertEquals(FetchFailure.Kind.CONNECT_TIMEOUT, FetchFailure.classify(java.net.SocketTimeoutException("failed to connect to host (port 443) after 30000ms")))
        assertEquals(FetchFailure.Kind.RESET, FetchFailure.classify(java.net.SocketException("Connection reset")))
        assertEquals(FetchFailure.Kind.RESET, FetchFailure.classify(java.io.EOFException()))
        assertEquals(FetchFailure.Kind.REFUSED, FetchFailure.classify(java.net.ConnectException("Connection refused")))
        assertEquals(FetchFailure.Kind.DNS, FetchFailure.classify(java.net.UnknownHostException("h")))
        assertEquals(FetchFailure.Kind.OTHER, FetchFailure.classify(IllegalStateException("x")))
    }

    @Test fun failureLineHasTimeAndBytes() {
        assertEquals("таймаут чтения после 30000 мс, получено 812 КБ", FetchFailure.describe(java.net.SocketTimeoutException("Read timed out"), 30000, 812L * 1024))
        assertEquals("сброс соединения после 41 мс, получено 0 Б", FetchFailure.describe(java.net.SocketException("Connection reset"), 41, 0))
        assertTrue(FetchFailure.describe(java.net.SocketTimeoutException("Read timed out"), 30000, 5, afterLimit = true).contains("общий лимит сбора"))
    }

    @Test fun failureLineNeverContainsErrorTextOrAddress() {
        val line = FetchFailure.describe(IllegalStateException("connect to 203.0.113.7:443 failed"), 10, 0)
        assertEquals("ошибка IllegalStateException после 10 мс, получено 0 Б", line)
        assertFalse(line.contains("203.0.113.7"))
    }

    @Test fun countingInputCountsWhatCameBeforeTheError() {
        val src = object : java.io.InputStream() {
            var left = 3000
            override fun read(): Int = if (left > 0) { left--; 1 } else throw java.net.SocketTimeoutException("Read timed out")
        }
        val c = CountingInput(src)
        try { c.readBytes() } catch (_: java.net.SocketTimeoutException) { }
        assertEquals(3000L, c.count)
    }

    @Test fun logScenarioBadSourcesDoNotKillVpnForTheRest() {
        // журнал 24.11 20:40: 3 плохих источника подряд (код 0), потом ещё 6 хороших; VPN жив
        val sim = Sim().apply { controlAlive = true }
        repeat(3) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0, bytes = 0, host = "https://bad/") }
        val got = (1..6).count { sim.fetch(directOk = false, vpnOk = true, host = "https://good/") }
        assertEquals(6, got)
        assertEquals(9, sim.vpnAttempts)
        assertTrue(sim.orders.none { it == FetchRoute.Order.DIRECT_ONLY })
    }

    // --- возврат к VPN, если связь восстановилась посреди сбора (журнал 24.11, сбор 20:59) ---

    private class Clock { var t = 0L }

    /** Маршрут, ушедший в DIRECT_ONLY в момент 0 (три кода 0 без байт и неотвеченная контрольная проверка). */
    private fun deadRoute(clock: Clock): FetchRoute {
        val r = FetchRoute { clock.t }
        repeat(3) { r.vpnResult(false, 0, afterDirectFail = true) }
        assertNotNull(r.takeControl(FALLBACK))
        assertEquals(FetchRoute.Control.WENT_DIRECT_ONLY, r.controlResult(false))
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
        return r
    }

    @Test fun directOnlyReturnsToVpnAfter15sWhenControlAnswers() {
        val c = Clock()
        val r = deadRoute(c)
        c.t = 15_000
        assertNotNull(r.takeControl(FALLBACK))
        assertEquals(FetchRoute.Control.RETURNED, r.controlResult(true))
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
        assertEquals(FetchRoute.Order.DIRECT_THEN_VPN, r.order(vpnUsable = true))
        assertTrue(r.summary()!!.contains("возвращался к VPN: 1"))
        assertFalse(r.summary()!!.contains("только напрямую"))
    }

    @Test fun directOnlyStaysWhenRecheckFails() {
        val c = Clock()
        val r = deadRoute(c)
        c.t = 15_000
        assertNotNull(r.takeControl(FALLBACK))
        assertEquals(FetchRoute.Control.STILL_DIRECT_ONLY, r.controlResult(false))
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
        assertEquals(FetchRoute.Order.DIRECT_ONLY, r.order(vpnUsable = true))
        // следующая проверка отсчитывается от неудачной, а не от первого перехода
        c.t = 29_999
        assertNull(r.takeControl(FALLBACK))
        c.t = 30_000
        assertNotNull(r.takeControl(FALLBACK))
    }

    @Test fun noRecheckBefore15s() {
        val c = Clock()
        val r = deadRoute(c)
        for (t in listOf(0L, 1_000L, 14_999L)) { c.t = t; assertNull(r.takeControl(FALLBACK)) }
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
    }

    @Test fun recheckLimitIsFivePerCollect() {
        val c = Clock()
        val r = deadRoute(c) // проверка №1 (перешёл в DIRECT_ONLY)
        var used = 1
        repeat(10) {
            c.t += 15_000
            if (r.takeControl(FALLBACK) != null) { used++; r.controlResult(false) }
        }
        assertEquals(FetchRoute.MAX_CONTROLS, used)
        assertEquals(5, used)
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
    }

    @Test fun onlyOneRecheckRunsAtATime() {
        val c = Clock()
        val r = deadRoute(c)
        c.t = 15_000
        assertNotNull(r.takeControl(FALLBACK))
        assertNull(r.takeControl(FALLBACK))
        r.controlResult(true)
        assertNull(r.takeControl(FALLBACK)) // VPN вернулся, проверять нечего
    }

    @Test fun retryListHasOnlySourcesNotTriedThroughVpn() {
        val c = Clock()
        val r = deadRoute(c)
        r.noteSkippedVpn("https://a/1")
        r.noteSkippedVpn("https://a/2")
        r.noteSkippedVpn("https://a/3")
        // a/2 всё-таки пробовали через VPN (например, второй попыткой после возврата связи) — повторять не нужно
        r.noteVpnTried("https://a/2")
        assertTrue(r.hasRetries())
        // пока путь закрыт, список не выдаётся и не теряется
        assertEquals(emptyList<String>(), r.takeRetries())
        c.t = 15_000
        r.takeControl(FALLBACK); r.controlResult(true)
        assertEquals(listOf("https://a/1", "https://a/3"), r.takeRetries())
        // выдаётся один раз
        assertEquals(emptyList<String>(), r.takeRetries())
        assertFalse(r.hasRetries())
    }

    @Test fun skippedSourcesAreNotNotedWhileVpnPathIsOpen() {
        val r = FetchRoute()
        r.noteSkippedVpn("https://a/1")
        assertFalse(r.hasRetries())
    }

    @Test fun logScenarioVpnComesBackMidCollect() {
        // журнал 24.11 20:59: обрыв подтверждён, через 16 с связь вернулась; остальные источники идут через VPN, а не только напрямую
        val c = Clock()
        val sim = Sim(FetchRoute { c.t })
        repeat(3) { sim.fetch(directOk = false, vpnOk = false, vpnCode = 0) } // контроль мёртв
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, sim.route.mode())
        c.t = 16_000
        sim.controlAlive = true
        assertNotNull(sim.route.takeControl(FALLBACK)) // то же, что Collector делает перед очередной загрузкой
        sim.route.controlResult(true)
        val got = (1..5).count { sim.fetch(directOk = false, vpnOk = true) }
        assertEquals(5, got)
        assertTrue(sim.orders.takeLast(5).none { it == FetchRoute.Order.DIRECT_ONLY })
    }

    // --- ConnectException по таймауту ---

    @Test fun connectExceptionAfterTimeoutIsConnectTimeout() {
        val e = java.net.ConnectException("failed to connect to host (port 443) after 30000ms")
        assertEquals(FetchFailure.Kind.CONNECT_TIMEOUT, FetchFailure.classify(e, 30_005, 30_000))
        assertTrue(FetchFailure.describe(e, 30_005, 0, timeoutMs = 30_000).startsWith("таймаут соединения после 30005 мс"))
    }

    @Test fun quickConnectExceptionKeepsItsClassName() {
        val e = java.net.ConnectException("failed to connect to host (port 443) after 30000ms")
        assertEquals("ошибка ConnectException после 55 мс, получено 0 Б", FetchFailure.describe(e, 55, 0, timeoutMs = 30_000))
    }

    @Test fun refusedStaysRefusedEvenAfterLongWait() {
        val e = java.net.ConnectException("failed to connect: ECONNREFUSED (Connection refused)")
        assertEquals(FetchFailure.Kind.REFUSED, FetchFailure.classify(e, 30_000, 30_000))
    }

    // Д3: лимит контрольных проверок исчерпан — что делать с дальнейшими неудачами определяет результат последней проверки
    private fun failSeries(r: FetchRoute, n: Int = 3) { repeat(n) { r.vpnResult(false, 0, afterDirectFail = false) } }

    @Test fun lastControlAliveThenMoreFailuresKeepsVpn() {
        val r = FetchRoute()
        repeat(FetchRoute.MAX_CONTROLS) {
            failSeries(r)
            assertNotNull(r.takeControl(FALLBACK))
            r.controlResult(true)
        }
        failSeries(r)
        assertNull(r.takeControl(FALLBACK))
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
    }

    @Test fun lastControlDeadThenMoreFailuresStaysDirectOnly() {
        val r = FetchRoute()
        repeat(FetchRoute.MAX_CONTROLS - 1) { failSeries(r); assertNotNull(r.takeControl(FALLBACK)); r.controlResult(true) }
        failSeries(r)
        assertNotNull(r.takeControl(FALLBACK))
        r.controlResult(false)
        failSeries(r)
        assertNull(r.takeControl(FALLBACK))
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
    }

    // Д3: после исчерпанного лимита (последняя проверка «жив») VPN умирает — судим по серии пустых неудач
    private fun exhaustedAlive(): FetchRoute {
        val r = FetchRoute()
        repeat(FetchRoute.MAX_CONTROLS) { failSeries(r); assertNotNull(r.takeControl(FALLBACK)); r.controlResult(true) }
        return r
    }
    private fun emptyFail(r: FetchRoute, n: Int) { repeat(n) { r.vpnResult(false, 0, afterDirectFail = false); r.takeControl(FALLBACK) } }

    @Test fun sixEmptyFailuresAfterLimitGoDirectOnly() {
        val r = exhaustedAlive()
        emptyFail(r, 6)
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
        assertNull(r.takeControl(FALLBACK)) // новых запросов нет
        assertTrue(r.summary()!!.contains("после исчерпанного лимита"))
    }

    @Test fun fiveEmptyFailuresAfterLimitDoNot() {
        val r = exhaustedAlive()
        emptyFail(r, 5)
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
    }

    @Test fun failureWithBytesResetsTheCount() {
        val r = exhaustedAlive()
        emptyFail(r, 5)
        r.vpnResult(false, 0, afterDirectFail = false, bytes = 1024)
        emptyFail(r, 5)
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
        emptyFail(r, 1)
        assertEquals(FetchRoute.Mode.DIRECT_ONLY, r.mode())
    }

    @Test fun httpCodeResetsTheCountToo() {
        val r = exhaustedAlive()
        emptyFail(r, 5)
        r.vpnResult(false, 404, afterDirectFail = false)
        emptyFail(r, 5)
        assertTrue(r.mode() != FetchRoute.Mode.DIRECT_ONLY)
    }
}
