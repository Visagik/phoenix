package com.phoenix.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Сообщает работающему VPN, что запасные серверы целей обновились и маршруты нужно пересчитать. */
object PlanBus {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}

/**
 * Периодическая перепроверка: лучшие конфиги (задержка и сервисы), скорость нескольких лучших,
 * запасные серверы для каждой цели.
 */
/** Сколько проверок ядро ведёт одновременно: каждая поднимает отдельный экземпляр Xray. */
object CheckLimits {
    fun parallel(context: Context): Int {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        return MemGuard.scale(if (am != null && am.isLowRamDevice) 3 else 6, MemInfo.currentPssMb())
    }

    /** Сервисов одного конфига одновременно: в мобильной сети и на слабых телефонах — по одному. */
    fun inner(context: Context): Int {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        return if ((am != null && am.isLowRamDevice) || NetInfo.label(context).startsWith("mobile")) 1 else 3
    }

    /** Для поиска рабочего сервера, когда пользователь ждёт: больше параллельных проверок (в v2rayNG по умолчанию 16). */
    fun searchParallel(context: Context): Int {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        return MemGuard.scale(if (am != null && am.isLowRamDevice) 5 else 16, MemInfo.currentPssMb())
    }
}

object TargetsRunner {
    const val CHECK_TOP = 15
    const val SPEED_TOP = 3
    const val TARGET_POOL = 25
    /** Для целей, у которых не нашлось ни одного сервера: шире пул и больше проверок (в белых списках рабочих мало, и нужный может быть глубже). */
    const val TARGET_WIDE_POOL = 90
    const val TARGET_DEEP_TESTS = 36
    const val TARGET_DEEP_MAX = 5

    /** Последняя попытка для цели без серверов: любые конфиги режима (в т. ч. с «мёртвой» отметкой из другой сети), не больше одной цели за прогон. */
    const val TARGET_FULL_TESTS = 60
    const val DISCOVER_MIN_ALIVE = 20
    const val DISCOVER_WANT = 30
    const val DISCOVER_BUDGET_MS = 120_000L

    private fun speedAllowed(context: Context, settings: AppSettings): Boolean {
        if (BackgroundBudgetGate.exhausted(context)) return false
        if (settings.speedOnMetered) return true
        return NetInfo.isUnmetered(context)
    }

    /** Открывается ли адрес без VPN (само приложение исключено из туннеля, поэтому запрос идёт напрямую). */
    private fun directOpens(url: String): Boolean = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 6_000
        c.instanceFollowRedirects = true
        c.requestMethod = "GET"
        try {
            Soft.accepts(c.responseCode)
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun reasonsText(r: Map<String, Int>): String = if (r.isEmpty()) "" else " [не подошли: " + r.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" } + "]"

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Три независимых уникальных задачи (разовая, повтор, плановая) могут дойти до запуска почти одновременно —
     * ни одна из них сама по себе от этого не защищена. Вторая, застав первую уже внутри, не бежит параллельно
     * (двойная нагрузка на сеть и ядро), а тихо выходит: первая и так обновит все те же файлы.
     */
    suspend fun run(context: Context, withSpeed: Boolean = true): String {
        if (!running.compareAndSet(false, true)) return "уже выполняется — пропускаю"
        try {
            return runInner(context, withSpeed)
        } finally {
            running.set(false)
        }
    }

    private suspend fun runInner(context: Context, withSpeed: Boolean = true): String {
        if (!XrayCore.available) return "ядро недоступно"
        val storage = Storage(context)
        val settings = AppSettings(context)
        val rows = ConfigRepository.load(storage)
        if (rows.isEmpty()) return "нет конфигов"

        val date = LocalDate.now().toString()
        val checkStore = CheckStore(storage.checksFile)
        val probe: (String, String) -> Long = { cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(context, cfg, url) } }
        val notes = ArrayList<String>()
        val guard = RunGuard(storage.mode, NetInfo.label(context))
        val runStart = System.currentTimeMillis()
        val stopped = { StopRequest.requestedSince(runStart) }
        val stale = { guard.stale(Storage(context).mode, NetInfo.label(context)) || stopped() }
        val history = storage.history()
        val ctx = NetInfo.label(context)
        val hview = { history.view(ctx) } // читаем заново: опыт пополняется по ходу работы

        // 0. поиск живых среди всех: из ~1300 конфигов отвечают десятки, а «лучшие по рейтингу» могут оказаться мёртвыми.
        //    Если свежих живых мало — быстрый поиск (TCP, затем задержка) по всей очереди, результат запоминается.
        val known = Ranking.candidates(rows, checkStore.load(), history = hview(), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())
        // на мобильной сети после исчерпания суточного лимита поиск по сотням серверов не запускаем (журнал 20.11: лимит исчерпан в 12:49, а поиск 585 серверов шёл в 15:48)
        val budgetOut = BackgroundBudgetGate.exhausted(context)
        if (known.count { it.alive } < DISCOVER_MIN_ALIVE && !budgetOut) {
            val sink = SearchSink(storage, context)
            val gov = GovernedLimits.get(storage)
            val govNet = NetInfo.label(context)
            gov.begin(govNet)
            val search = ServerSearch(
                reach = { h, p -> ServerHosts.reach(h, p, 2_500) },
                probe = probe,
                tcpParallel = ParallelGovernor.capFor(gov.tcp(govNet), NetInfo.kind(context) == NetInfo.Kind.MOBILE),
                probeParallel = gov.probe(govNet, CheckLimits.parallel(context)),
                loadSample = GatewayProbe.sampler(context),
                shouldStop = stale,
                offlineSuspect = ServerSearch.offlineLimit(NetInfo.kind(context) == NetInfo.Kind.MOBILE),
            )
            // «мёртвые» отметки тоже перепроверяем: конфиг мог не отвечать в другой сети
            val order = SearchPool.build(context, storage, withForeign = false).filter { XrayConfig.supports(it.link) && !it.alive }
            val got = search.find(order, want = DISCOVER_WANT, budgetMs = DISCOVER_BUDGET_MS) { c, ms -> sink.add(c, ms) }
            sink.flush()
            gov.finish(govNet, System.currentTimeMillis()); gov.save()
            notes += "поиск: проверено ${sink.count}, живых найдено ${got.size}"
        }
        if (stale()) {
            storage.log("TARGETS: за время поиска сменились сеть или режим — результаты не записываю, повторю позже")
            return notes.joinToString(", ") + (if (notes.isEmpty()) "" else ", ") + "прервано: сеть или режим сменились"
        }

        // 1. свежая проверка лучших конфигов: реальная задержка и выбранные сервисы
        val ranked = Ranking.rank(Ranking.candidates(rows, checkStore.load(), history = hview(), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds()))
        // CHECK_TOP разных серверов (а не конфигов): «братья» с одного сервера проверяются по цепочке внутри ServiceChecker
        val topHosts = ranked.map { it.host.ifEmpty { it.link } }.distinct().take(CHECK_TOP).toSet()
        val top = ranked.filter { it.host.ifEmpty { it.link } in topHosts }
        val services = storage.checkSelection().selected()
        val checked = ServiceChecker(probe, parallel = GovernedLimits.get(storage).probe(ctx, CheckLimits.parallel(context)), reach = { h, p -> ServerHosts.reach(h, p) }, soft = { l, u -> SpeedRunner.reaches(context, l, u) }, exit = { l -> AutoServices.exitOf(context, storage, l) }, region = { l, u -> SpeedRunner.regionBlocked(context, l, u) }, veto = { l, u -> ServiceVeto(storage.vetoFile).active(l, u) }, inner = CheckLimits.inner(context), note = { storage.log(it) }).check(top.map { it.link }, services, date) { key, res ->
            if (!stale()) checkStore.putAll(mapOf(key to res)) // при смене режима ядро перезапускается: «closed pipe» — не вердикт серверу
        }
        try { EnergyMeter.of(java.io.File(context.filesDir, "energy.tsv")).add("targets", top.size * services.size.coerceAtLeast(1), java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) { }
        if (stale()) {
            storage.log("CHECK: во время проверки сменились сеть или режим — итог не записываю")
            return notes.joinToString(", ") + (if (notes.isEmpty()) "" else ", ") + "прервано: сеть или режим сменились"
        }
        // опыт: что в этой сети сработало, что нет (ничего дополнительно не проверяем — берём итоги этой же проверки)
        history.record(top.mapNotNull { c -> checked[CheckStore.keyOf(c.link)]?.let { HistEvent(c.identity, ctx, !it.dead) } })
        notes += "проверено ${checked.size}, живых ${checked.values.count { !it.dead }}"
        try {
            val more = AutoServices.run(context, storage)
            if (more > 0) notes += "сервисы проверены ещё у $more"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            storage.log("SVC: ошибка автопроверки сервисов: ${e.message}")
        }
        if (checked.isNotEmpty() && checked.values.none { !it.dead }) {
            storage.log("CHECK: живых нет из ${checked.size}; последняя ошибка ядра: ${XrayCore.lastError.ifBlank { "нет (серверы не отвечают по TCP)" }}")
        } else storage.log("CHECK: проверено ${checked.size}, живых ${checked.values.count { !it.dead }}")

        // 2. скорость лучших живых (по Wi-Fi, если мобильную сеть не разрешили)
        if (withSpeed && speedAllowed(context, settings)) {
            var measured = 0
            val alive = Ranking.rank(Ranking.candidates(rows, checkStore.load(), history = hview(), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())).take(SPEED_TOP)
            for (c in alive) {
                val key = CheckStore.keyOf(c.link)
                val old = checkStore.load()[key]
                if (old != null && old.mbps >= 0 && old.date == date) continue
                val speed = SpeedRunner.run(context, c.link) ?: continue
                try { EnergyMeter.of(java.io.File(context.filesDir, "energy.tsv")).addSpeed(speed.bytes, date, System.currentTimeMillis()) } catch (_: Throwable) { }
                checkStore.putSpeed(key, speed, date, ConfigIdentity.address(c.link))
                history.record(c.identity, ctx, true, speed.mbps)
                measured++
            }
            notes += "скорость измерена у $measured"
        }

        // 3. запасные серверы целей
        val targetStore = TargetStore(storage.targetsFile)
        val targets = targetStore.load()
        if (targets.isNotEmpty()) {
            val pool = Ranking.rank(Ranking.candidates(rows, checkStore.load(), history = hview(), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())).take(TARGET_POOL)
            val verifier = TargetVerifier(probe, parallel = CheckLimits.parallel(context).coerceAtMost(3), reach = { h, p -> ServerHosts.reach(h, p) },
                soft = { link, url -> SpeedRunner.reaches(context, link, url) },
                exit = { link -> AutoServices.exitOf(context, storage, link) },
                region = { link, url -> SpeedRunner.regionBlocked(context, link, url) },
                veto = { link, url -> ServiceVeto(storage.vetoFile).active(link, url) },
                stop = stopped)
            var updated = 0
            val selected = storage.checkSelection()
            val services = selected.all()
            val checksNow = checkStore.load()
            val wide = Ranking.rank(Ranking.candidates(rows, checksNow, history = hview(), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())).take(TARGET_WIDE_POOL)
            val missMap = HashMap<String, Long>(try { MissBackoff.parse(if (storage.missFile.exists()) storage.missFile.readText() else "") } catch (_: Throwable) { emptyMap() })
            val nowMs = System.currentTimeMillis()
            var deepLeft = if (budgetOut) 0 else TARGET_DEEP_MAX // глубокий и последний поиск — тоже только пока есть суточный лимит мобильного трафика
            var fullLeft = if (budgetOut) 0 else 1
            val nameOf = rows.associate { it.raw to it.displayName }
            val concrete = { l: List<Candidate> -> ServerKind.concreteFirst(l) { c -> nameOf[c.link].orEmpty() } }
            // приложения, которые в этой сети идут мимо VPN (открываются напрямую), сервера не требуют: подбор — там, где они закрыты (мобильная сеть)
            val bypassNow = try { BypassStore(storage.base, storage.mode, NetInfo.label(context)).effective() } catch (_: Throwable) { emptySet() }
            var tIdx = 0
            var foundN = 0
            var noneN = 0
            var directN = 0
            var pausedN = 0
            var testedAll = 0
            val showDetail = { LiveActivity.targetsDetail.value = TargetsDetail(tIdx, targets.size, foundN, noneN, directN, testedAll, pausedN) }
            for (t in targets) {
                tIdx++
                LiveActivity.targets.value = tr("Подбираю серверы для целей: «{}» ({}/{})", t.label, tIdx, targets.size)
                showDetail()
                if (stopped()) {
                    storage.log("TARGET: остановлено вами — остальные цели отложены, найденное сохранено")
                    break
                }
                if (stale()) {
                    storage.log("TARGET: сменились сеть или режим — остальные цели отложены")
                    break
                }
                if (t.kind == TargetKind.APP && t.value in bypassNow) {
                    storage.log("TARGET: «${t.label}» — в этой сети приложение идёт мимо VPN (открывается напрямую), сервер не подбираю")
                    directN++
                    continue
                }
                // серверы, через которые нужный сервис (YouTube, Claude…) уже открывался, пробуем первыми, не открывавшийся — последними
                val svc = ServiceMatch.serviceFor(t.checkUrl, services)
                // «Auto» — выбор самой подписки, страна выхода у него плавает: для сервисов с проверкой региона (Gemini, ChatGPT…) не годится
                val geo = GeoGate.applies(t.checkUrl)
                val skipAuto: (String) -> Boolean = { l -> geo && ServerKind.isFloatingExit(nameOf[l] ?: LinkParser.parse(l)?.title?.let { urlDecode(it) }.orEmpty()) }
                val cl = { l: List<Candidate> -> concrete(l) }
                var servers = verifier.verify(t, cl(ServiceMatch.order(ServiceMatch.strict(pool, checksNow, svc), checksNow, svc)), skip = skipAuto)
                var tested = verifier.lastTested
                // цель, для которой недавно ничего не нашлось даже глубоким поиском, не перебирается заново каждый прогон (в логе — 3–5 минут впустую)
                var paused = servers.isEmpty() && MissBackoff.paused(missMap, t.id, nowMs)
                // длинная пауза держится, только пока сайт открывается напрямую; пропал прямой доступ — ищем сервер снова
                if (paused && !MissBackoff.skip(missMap, t.id, nowMs) && !directOpens(t.checkUrl)) {
                    paused = false
                    missMap.remove(MissBackoff.directKey(t.id))
                }
                if (paused) pausedN++
                if (paused) storage.log("TARGET: для «${t.label}» глубокий поиск недавно ничего не дал — пауза (6 часов, для «мимо VPN» сутки)")
                if (servers.isEmpty() && !paused && deepLeft > 0 && wide.size > pool.size) {
                    deepLeft--
                    servers = verifier.verify(t, cl(ServiceMatch.order(wide, checksNow, svc)), maxTests = TARGET_DEEP_TESTS, skip = skipAuto)
                    tested += verifier.lastTested
                    storage.log("TARGET: для «${t.label}» среди лучших ничего — искал глубже по ${wide.size} конфигам" + (if (svc != null) " (сервис ${svc.id})" else "") + reasonsText(verifier.lastReasons))
                }
                if (servers.isEmpty() && !paused && fullLeft > 0) {
                    fullLeft--
                    // в чёрных списках берём и серверы белых: они работают и в обычной сети (для Gemini/NotebookLM «чистых» выходов мало)
                    val all = SearchPool.build(context, storage).filter { XrayConfig.supports(it.link) }
                    servers = verifier.verify(t, cl(ServiceMatch.order(all, checksNow, svc)), maxTests = TARGET_FULL_TESTS, skip = skipAuto)
                    tested += verifier.lastTested
                    storage.log("TARGET: для «${t.label}» последняя попытка по ${all.size} конфигам" + (if (storage.mode == Mode.BLACKLIST) " (своего режима и белых списков)" else " всего режима") + reasonsText(verifier.lastReasons))
                }
                // ни один сервер не открыл сайт, а напрямую (мимо VPN) он открывается — пускаем цель мимо VPN
                if (servers.isEmpty() && directOpens(t.checkUrl)) {
                    servers = listOf(Target.DIRECT)
                    storage.log("TARGET: «${t.label}» — через серверы сайт не открылся, а напрямую открывается: пущу мимо VPN")
                }
                // «мимо VPN» — не найденный сервер: пауза поиска от него не снимается (иначе каждый прогон заново перебирал все конфиги, находка №9)
                val onlyDirect = servers.isNotEmpty() && servers.all { it == Target.DIRECT }
                if ((servers.isEmpty() || onlyDirect) && !paused) {
                    missMap[t.id] = nowMs
                    if (onlyDirect) missMap[MissBackoff.directKey(t.id)] = nowMs else missMap.remove(MissBackoff.directKey(t.id))
                } else if (servers.isNotEmpty() && !onlyDirect) {
                    missMap.remove(t.id); missMap.remove(MissBackoff.directKey(t.id))
                }
                try { storage.missFile.writeTextAtomic(MissBackoff.render(missMap)) } catch (_: Throwable) { }
                testedAll += tested
                // остановили посреди подбора: «ничего не нашлось» тут не итог, паузу поиска для цели не ставим
                if (stopped() && servers.isEmpty()) {
                    storage.log("TARGET: остановлено вами на «${t.label}» — результата нет, паузу поиска не ставлю")
                    break
                }
                if (servers.isEmpty()) noneN++ else if (onlyDirect) directN++ else foundN++
                showDetail()
                // если ничего не нашлось (например, пропала сеть), прежние серверы не затираем
                if (servers.isNotEmpty()) {
                    targetStore.updateServers(t.id, servers, date)
                    updated++
                    val first = LinkParser.parse(servers.first())?.title?.let { urlDecode(it) }.orEmpty()
                    // прямой маршрут — не сервер: в счёт рабочих серверов он не входит
                    val direct = servers.count { it == Target.DIRECT }
                    val proxies = servers.size - direct
                    storage.log("TARGET: «${t.label}» — рабочих серверов $proxies" + (if (direct > 0) " (+ напрямую мимо VPN)" else "") + ", первый [$first], проверено $tested, адрес ${t.checkUrl}")
                } else {
                    storage.log(
                        "TARGET: для «${t.label}» рабочих серверов не найдено (проверено $tested серверов, кандидатов всего ${wide.size}, адрес ${t.checkUrl}), " +
                            if (t.servers.isEmpty()) "прежних нет — приложение идёт через общий сервер" else "прежние (${t.servers.size}) оставлены"
                    )
                }
            }
            notes += "цели: обновлено $updated из ${targets.size}"
            PlanBus.notifyChanged()
        }
        return notes.joinToString(", ")
    }
}

class TargetsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // плановый запуск раз в 6 часов — тяжёлый: делаем на зарядке (или если давно не делали и заряд хороший)
        if (inputData.getBoolean("auto", false) && !PowerInfo.heavyAllowed(applicationContext, AppSettings(applicationContext).lastTargetsAt)) {
            Storage(applicationContext).log("TARGETS: плановая перепроверка отложена — телефон не на зарядке")
            return Result.success()
        }
        LiveActivity.targets.value = tr("Подбираю серверы для целей…")
        val summary = try {
            TargetsRunner.run(applicationContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // система остановила работу (смена сети при подключении VPN, низкий заряд): повторим позже, а не считаем ошибкой
            // причина остановки (WorkManager 2.9+): без неё не понять, что именно прерывает проверку целей
            val why = try { " (причина $stopReason)" } catch (_: Throwable) { "" }
            Storage(applicationContext).log("TARGETS: прервано системой$why, повторю через 10 минут")
            LiveActivity.targets.value = ""
            LiveActivity.targetsDetail.value = null
            enqueueRetry(applicationContext)
            throw e
        } catch (e: Exception) {
            "ошибка: ${e.message}"
        }
        LiveActivity.targets.value = ""
        LiveActivity.targetsDetail.value = null
        AppSettings(applicationContext).lastTargetsAt = System.currentTimeMillis()
        Storage(applicationContext).log("TARGETS: $summary")
        LocalSocks.statsLine()?.let { Storage(applicationContext).log(it) }
        return Result.success()
    }

    companion object {
        private const val ONCE = "phoenix_targets"
        private const val PERIODIC = "phoenix_targets_periodic"

        // фоновая перепроверка запускает ядро десятки раз — не делаем этого при разряженной батарее
        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Разовая перепроверка (после ночного сбора, по кнопке). */
        /** [delaySec] — подождать, пока закончится пересборка туннеля: подбор, запущенный в тот же миг, снимала система (журнал 20.11: «прервано системой (причина 7)» через 3–7 с после «пересоздаю туннель»). */
        fun enqueue(context: Context, delaySec: Long = 0L) {
            val builder = OneTimeWorkRequestBuilder<TargetsWorker>().setConstraints(constraints())
            if (NetFlap.busy(System.currentTimeMillis())) builder.setInitialDelay(3, TimeUnit.MINUTES) // сеть меняется часто — не начинать сейчас
            else if (delaySec > 0) builder.setInitialDelay(delaySec, TimeUnit.SECONDS)
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, builder.build())
        }

        /** После свежего сбора или перепроверки: полный поиск для целей чаще раза в 30 минут только тратит сеть (журнал: три поиска за 50 минут на мобильной). */
        fun enqueueAfterRefresh(context: Context) {
            val ago = System.currentTimeMillis() - AppSettings(context).lastTargetsAt
            if (ago in 0 until MIN_GAP_MS) {
                Storage(context).log("TARGETS: поиск для целей пропущен — был ${ago / 60_000} мин назад (следующий не раньше чем через ${MIN_GAP_MS / 60_000})")
                return
            }
            enqueue(context)
        }

        private const val MIN_GAP_MS = 30 * 60_000L

        private fun enqueueRetry(context: Context) {
            val request = OneTimeWorkRequestBuilder<TargetsWorker>()
                .setConstraints(constraints())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("phoenix_targets_retry", ExistingWorkPolicy.KEEP, request)
        }

        /** Раз в 6 часов: серверы целей могут отвалиться и в течение дня. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TargetsWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setInputData(androidx.work.workDataOf("auto" to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
