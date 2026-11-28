package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Состояние полной перепроверки для интерфейса. */
object RecheckState {
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow("")

    /** Подробности хода: пусто, пока перепроверка не идёт или ещё не проверен ни один сервер. */
    val detail = MutableStateFlow<RecheckDetail?>(null)

    /** Последний запуск остановился по времени, не дойдя до конца круга (не из-за ошибки и не вами): стоит продолжить. */
    @Volatile var roundOpen = false
}

/**
 * Откуда брать серверы, когда нужно найти рабочий. Берутся ВСЕ конфиги режима: пометка «когда-то не отвечал» не выбрасывает
 * конфиг (он мог не отвечать в другой сети), а лишь ставит его в очередь позже. В режиме чёрных списков дальше идут конфиги
 * белых списков: серверы белых списков работают и в обычной сети.
 */
object SearchPool {
    fun build(context: Context, storage: Storage, withForeign: Boolean = storage.mode == Mode.BLACKLIST && !LiteProfile.ON): List<Candidate> {
        val own = poolOf(context, storage, storage.mode)
        if (!withForeign) return own
        val seen = own.map { it.identity }.toHashSet()
        val extra = Mode.entries.filter { it != storage.mode }
            .flatMap { poolOf(context, Storage(context, it), null).map { c -> c.copy(foreign = true) } }
            .filter { seen.add(it.identity) }
        return own + extra
    }

    /** Сначала свои конфиги режима, потом чужие — отдельными списками, чтобы результаты сохранялись в данные своего режима. */
    fun groups(context: Context, storage: Storage, withForeign: Boolean = storage.mode == Mode.BLACKLIST && !LiteProfile.ON): List<Pair<Storage, List<Candidate>>> {
        val out = ArrayList<Pair<Storage, List<Candidate>>>()
        out += storage to poolOf(context, storage, storage.mode)
        if (withForeign) for (m in Mode.entries) if (m != storage.mode) {
            val st = Storage(context, m)
            out += st to poolOf(context, st, null)
        }
        return out
    }

    private fun poolOf(context: Context, storage: Storage, mode: Mode?): List<Candidate> {
        val rows = ConfigRepository.load(storage)
        val checks = CheckStore(storage.checksFile).load()
        return Ranking.rank(Ranking.candidates(rows, checks, history = storage.historyView(context), mode = mode, includeDead = true, priority = storage.priorityView(), svcIds = storage.selectedServiceIds()))
    }
}

/** Принудительная перепроверка всех конфигов: TCP-отсев, потом настоящая задержка через ядро; итоги идут в проверки и опыт. */
object Recheck {
    /** Подпись ручного запуска: единственное место, где она записана; ручной ли запуск, решает [isManual], а не сравнение текста по месту. */
    const val MANUAL = "вручную"

    fun isManual(reason: String): Boolean = reason == MANUAL

    const val BUDGET_MS = 25 * 60_000L

    /** Без службы переднего плана система обрывает работу через 10 минут: идём частями по 8 и ставим продолжение. */
    const val NO_FOREGROUND_BUDGET_MS = 8 * 60_000L
    const val CONTINUE_REASON = "продолжение"
    const val MAX_CONTINUATIONS = 8
    const val MIN_GAP_MS = 30 * 60_000L
    const val RETRY_DELAY_SEC = 120L

    suspend fun runAll(context: Context, mode: Mode, budgetMs: Long = BUDGET_MS, withForeign: Boolean = false, reason: String = MANUAL): String {
        if (!XrayCore.available) return "ядро недоступно"
        if (!RecheckState.running.compareAndSet(false, true)) return "перепроверка уже идёт"
        val started = System.currentTimeMillis()
        RecheckState.roundOpen = false
        val storage = Storage(context, mode)
        if (NetInfo.kind(context) == NetInfo.Kind.NONE || FailoverGate.offlineRecently(ModeDetector.offlineAt, started)) {
            // без сети все конфиги «не отвечают», и перепроверка испортила бы приоритеты (в логе: живых 0 из 677)
            storage.log("RECHECK: пропущена ($reason) — у телефона нет интернета")
            RecheckState.running.value = false; RecheckState.detail.value = null
            return "нет сети"
        }
        val netBefore = NetInfo.label(context)
        try {
            AppSettings(context).lastRecheckAt = started
            val allGroups = SearchPool.groups(context, storage, withForeign)
                .map { (st, list) -> st to list.filter { XrayConfig.supports(it.link) } }
            val quarantineSkipped = allGroups.sumOf { (_, l) -> l.count { RecheckSkip.quarantined(it) } }
            // круг перепроверки (Н1): уже проверенные в этом круге (та же сеть, не старше 6 часов) пропускаем — следующий запуск идёт дальше, а не с верха списка
            val roundFileOf = { s: Storage -> java.io.File(s.checksFile.parentFile, RecheckRound.FILE_NAME) }
            val resumes = HashMap<Mode, RecheckRound.Round>()
            fun pools(useResume: Boolean) = allGroups.map { (st, l) ->
                val ok = l.filterNot { RecheckSkip.quarantined(it) }
                val prev = if (useResume) RecheckRound.load(roundFileOf(st)).takeIf { RecheckRound.resumable(it, netBefore, started) } else null
                if (prev != null) resumes[st.mode] = prev else resumes.remove(st.mode)
                val rest = if (prev != null) RecheckRound.remaining(ok, prev.keys) { CheckStore.keyOf(it.link) } else ok
                val cut = LiteProfile.capRecheck(rest) // Lite: только лучшие по рейтингу (список уже отсортирован)
                if (cut.size < rest.size) storage.log("LITE: в перепроверку взял ${cut.size} лучших из ${rest.size}")
                st to cut
            }
            var notQuarantined = pools(true)
            if (resumes.isNotEmpty() && notQuarantined.sumOf { it.second.size } == 0) {
                // прежний круг уже пройден целиком: начинаем новый
                allGroups.forEach { (st, _) -> try { roundFileOf(st).delete() } catch (_: Throwable) { } }
                notQuarantined = pools(false)
            }
            // крупные источники (>500 конфигов) — «скамейка запасных»: проверяются, только если основной части не хватило живых
            // источники с высоким приоритетом (вручную или по авто-оценке) на скамейку не уходят, даже если крупные
            fun highSources(st: Storage): Set<String> = try {
                AutoPriority.effective(SourcePriorities(st.sourcePriorityFile).load(), SourcePriorities(st.sourceAutoPriorityFile).load())
                    .filterValues { it >= SourcePriority.HIGH }.keys
            } catch (_: Exception) { emptySet() }
            val benchGroups = ArrayList<Pair<Storage, List<Candidate>>>()
            val groups = notQuarantined.map { (st, l) ->
                val benchIds = try {
                    if (st.sourceMembersFile.exists()) SourceBench.benchIds(SourceStats.decode(st.sourceMembersFile.readText()), UserSources(st.userSourcesFile).load().toSet(), highSources(st)) else emptySet()
                } catch (_: Exception) { emptySet() }
                val (main, bench) = SourceBench.split(l, benchIds) { CheckStore.keyOf(it.link) }
                if (bench.isNotEmpty()) benchGroups += st to bench
                st to main
            }
            val benchCount = benchGroups.sumOf { it.second.size }
            var totalCount = groups.sumOf { it.second.size }
            if (totalCount == 0) {
                RecheckState.progress.value = "Нет конфигов для проверки"
                return "нет конфигов"
            }
            for ((m, r) in resumes) Storage(context, m).log(RecheckRound.resumeLine(r) { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(it)) })
            storage.log(
                "RECHECK: старт ($reason), режим ${mode.title}, конфигов $totalCount" +
                    groups.joinToString("") { (st, l) -> ", ${if (st.mode == mode) "свои" else "из режима «${st.mode.title}»"} ${l.size}" } +
                    (if (withForeign) "" else " (только этот режим)") + (if (benchCount > 0) "; запасных из крупных источников $benchCount (проверю, если основных не хватит)" else "") +
                    (if (quarantineSkipped > 0) "; пропущено $quarantineSkipped: недавно мёртвые и в этой сети подряд не работали" else "")
            )
            val reachCache = ConcurrentHashMap<String, String>()
            val done = AtomicInteger()
            val probedJudged = AtomicInteger() // только запуски ядра (ms ≥ 0 и −1): по ним судит RunGuard.hopeless
            val alive = AtomicInteger()
            val tcpDead = AtomicInteger()
            val skippedLive = AtomicInteger()
            var lastDetailAt = 0L
            val guard = RunGuard(mode, NetInfo.label(context))
            var abortNote = ""
            var userStopped = false
            var budgetCut = false // время вышло, не все дошли до конца: круг остаётся в файле, следующий запуск продолжит
            val trackers = HashMap<Mode, RecheckRound.Tracker>()
            var checked = 0
            val queue = ArrayDeque(groups)
            var benchAdded = benchGroups.isEmpty()
            while (true) {
                if (queue.isEmpty()) {
                    if (!benchAdded) {
                        benchAdded = true
                        val tg = try { TargetStore(storage.targetsFile).load() } catch (_: Exception) { emptyList() }
                        val why = SourceBench.reason(alive.get(), SourceBench.activeCount(tg), SourceBench.shortOf(tg))
                        if (why != null) {
                            queue.addAll(benchGroups)
                            totalCount += benchCount
                            storage.log("RECHECK: достаю запасных из крупных источников ($benchCount): $why")
                        } else storage.log("RECHECK: запасных из крупных источников не трогаю — основных хватило (живых ${alive.get()})")
                    }
                    if (queue.isEmpty()) break
                }
                val (st, list) = queue.removeFirst()
                val left = budgetMs - (System.currentTimeMillis() - started)
                if (left <= 0) budgetCut = true
                if (left <= 0 || list.isEmpty()) continue
                val sink = SearchSink(st, context)
                val tracker = trackers.getOrPut(st.mode) {
                    val prev = resumes[st.mode]
                    RecheckRound.Tracker(roundFileOf(st), prev?.startedAt ?: started, netBefore, prev?.keys.orEmpty())
                }
                sink.onFlushed = { items -> tracker.add(items.map { CheckStore.keyOf(it.first.link) }); tracker.save() }
                val speed = AppSettings(context).recheckSpeed
                val probeBase = LiteProfile.probeCap(RecheckSpeed.probes(speed, CheckLimits.searchParallel(context)))
                val gov = GovernedLimits.get(st)
                val govNet = NetInfo.label(context)
                gov.begin(govNet)
                // ручной запуск и «Быстрая» — выбор человека: уровень, накопленный из-за сбоев сети, урезает их слабее
                val lvlCap = when { speed == RecheckSpeed.FAST -> 0; Recheck.isManual(reason) -> 1; else -> ParallelGovernor.MAX_LEVEL }
                val detailTcp = minOf(LiteProfile.tcpCap(RecheckSpeed.tcpCap(speed)), ParallelGovernor.capFor(gov.tcp(govNet, lvlCap), NetInfo.kind(context) == NetInfo.Kind.MOBILE))
                val detailProbe = gov.probe(govNet, ColdStart.recheckParallel(probeBase, AppSettings(context).lastSuccessAt), lvlCap)
                MemInfo.log(context, "перепроверка: старт, конфигов ${list.size}")
                val search = ServerSearch(
                    reach = { h, p ->
                        val key = "$h:$p"
                        val cached = reachCache[key]
                        if (cached != null) cached.ifEmpty { null }
                        else (ServerHosts.reach(h, p, RecheckSpeed.reachMs(speed))).also { reachCache[key] = it ?: "" }
                    },
                    probe = { cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(context, cfg, url, fast = mode == Mode.BLACKLIST) } },
                    tcpParallel = minOf(LiteProfile.tcpCap(RecheckSpeed.tcpCap(speed)), ParallelGovernor.capFor(gov.tcp(govNet, lvlCap), NetInfo.kind(context) == NetInfo.Kind.MOBILE)),
                    probeParallel = gov.probe(govNet, ColdStart.recheckParallel(probeBase, AppSettings(context).lastSuccessAt), lvlCap),
                    maxCandidates = 20_000,
                    onePerHost = false,
                    loadSample = GatewayProbe.sampler(context),
                    shouldStop = { StopRequest.requestedSince(started) || guard.stale(Storage(context).mode, NetInfo.label(context)) || RunGuard.hopeless(probedJudged.get(), alive.get()) },
                    offlineSuspect = ServerSearch.offlineLimit(NetInfo.kind(context) == NetInfo.Kind.MOBILE),
                    hostStrikes = RecheckSkip.HOST_STRIKES,
                )
                ProbeStats.begin() // этап 0: пять строк диагностики проб в конце прохода
                try {
                search.find(list, want = Int.MAX_VALUE, budgetMs = left) { c, ms ->
                    sink.add(c, ms)
                    if (ms >= 0) alive.incrementAndGet()
                    if (ms == ServerSearch.TCP_FAIL) tcpDead.incrementAndGet()
                    if (ms == ServerSearch.HOST_SKIPPED) skippedLive.incrementAndGet()
                    if (ms >= 0 || ms == -1L) probedJudged.incrementAndGet()
                    val n = done.incrementAndGet()
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastDetailAt >= 700 || n == totalCount) {
                        lastDetailAt = nowMs
                        val a = alive.get()
                        val td = tcpDead.get()
                        val sk = skippedLive.get()
                        RecheckState.detail.value = RecheckDetail(
                            total = totalCount, done = n, alive = a, tcpFailed = td, probeFailed = (n - a - td - sk).coerceAtLeast(0), skipped = sk,
                            quarantined = quarantineSkipped, elapsedMs = nowMs - started, speed = speed, tcpParallel = detailTcp, probeParallel = detailProbe,
                            level = gov.level(govNet), foreign = st.mode != mode,
                        )
                    }
                    if (n % 25 == 0) {
                        val eta = ActivityText.etaSeconds(n, totalCount, System.currentTimeMillis() - started)
                        RecheckState.progress.value = tr("Перепроверка: {} из {} ({}%), живых {}", n, totalCount, ActivityText.percent(n, totalCount), alive.get()) +
                            (when {
                                eta < 0 -> ""
                                eta < 60 -> " · " + tr("осталось ~{} с", eta)
                                eta < 3600 -> " · " + tr("осталось ~{} мин", (eta + 30) / 60)
                                else -> " · " + tr("осталось ~{} ч {} мин", eta / 3600, (eta % 3600) / 60)
                            })
                    }
                    if (sink.pending >= 150) sink.flush()
                }
                } finally {
                    // и при отмене (система остановила работу) последние результаты не теряются: запись синхронная
                    sink.flush()
                    ProbeStats.end()
                }
                if (!search.finished && !search.aborted && !search.suspectedOffline) budgetCut = true
                val offlineNow = search.suspectedOffline
                MemInfo.log(context, "перепроверка: конец")
                st.log("RECHECK: итог по этапам — хостов проверено по TCP ${search.triedTcp} (не открылись ${search.tcpFailed}), запусков ядра ${search.probed}, ответов без запуска (ProbeShare) ${ProbeShare.saved}, пропущено по адресу ${search.hostSkipped}")
                st.log("RECHECK: время по этапам — TCP в среднем ${if (search.triedTcp > 0) search.tcpMsTotal / search.triedTcp else 0} мс на хост, проба через ядро в среднем ${if (search.probed > 0) search.probeMsTotal / search.probed else 0} мс (живые ${if (search.probeOkCount > 0) search.probeOkMsTotal / search.probeOkCount else 0} мс ×${search.probeOkCount}, мёртвые ${if (search.probed > search.probeOkCount) (search.probeMsTotal - search.probeOkMsTotal) / (search.probed - search.probeOkCount) else 0} мс ×${(search.probed - search.probeOkCount).coerceAtLeast(0)}), притормаживал из-за нагрузки роутера ${search.throttled} раз, параллельно TCP ${ParallelGovernor.capFor(gov.tcp(govNet, lvlCap), NetInfo.kind(context) == NetInfo.Kind.MOBILE)}, проб ${gov.probe(govNet, probeBase, lvlCap)}, скорость ${speed}, уровень сети ${gov.level(govNet)}")
                ProbeStats.lines(detailProbe, govNet).forEach { st.log("RECHECK: $it") }
                if (offlineNow) {
                    // серверы подряд не открыли TCP — вероятнее всего, нет интернета у телефона, а не умерли серверы: ограничение не снимаем, а ужесточаем
                    if (gov.outage(govNet, System.currentTimeMillis(), startedAt = search.firstFailAt.takeIf { it > 0 } ?: -1L)) st.log("RECHECK: сеть $govNet потеряла интернет во время перепроверки — в следующий раз параллельных проверок будет меньше")
                    ModeDetector.offlineAt = System.currentTimeMillis()
                } else gov.finish(govNet, System.currentTimeMillis())
                gov.save()
                if (search.throttled > 0) st.log("RECHECK: роутер отвечал медленно — притормаживал проверки ${search.throttled} раз(а)")
                checked += sink.count
                if (offlineNow) {
                    abortNote = "похоже, у телефона нет интернета"
                    st.log("RECHECK: прервана — серверы подряд не открыли TCP, ${abortNote}")
                    break
                }
                if (search.aborted && StopRequest.requestedSince(started)) {
                    userStopped = true
                    st.log("RECHECK: остановлена вами — проверено ${sink.count} серверов, результаты сохранены")
                    break
                }
                if (search.aborted) {
                    abortNote = RunGuard.abortReason(guard.stale(Storage(context).mode, NetInfo.label(context)), probedJudged.get(), alive.get())
                    // «ни один не ответил» — серверы мертвы или замер сам прямой канал? Проверяем прямым замером: если и он ничего не открыл,
                    // виноват канал (журнал 22.11: 207 из 207 за 25 с сразу после сбора), и перепроверку повторим через две минуты
                    if (RunGuard.hopeless(probedJudged.get(), alive.get()) && !guard.stale(Storage(context).mode, NetInfo.label(context))) {
                        val counts = ModeDetector.measureConfirmed(context)
                        if (BlockDetector.classify(counts) == BlockType.OFFLINE) {
                            abortNote = "прямой канал замер (замер напрямую тоже ничего не открыл) — повторю через 2 мин"
                            ModeDetector.offlineAt = System.currentTimeMillis()
                            if (reason != "повтор") enqueueRetry(context, mode)
                        }
                    }
                    st.log("RECHECK: прервана — $abortNote")
                    break
                }
            }
            // прервана — результаты следующих этапов были бы ложными (журнал 05.11 11:01: после «прервана» — «SVC: … рабочих 0», «BYPASS: напрямую не открылось ничего»)
            val interrupted = abortNote.isNotEmpty()
            // круг дошёл до конца (или вы его остановили) — файл круга не нужен; иначе остаётся, и следующий запуск продолжит
            RecheckState.roundOpen = budgetCut && !interrupted && !userStopped
            if ((!interrupted && !budgetCut) || userStopped) allGroups.forEach { (st, _) -> try { roundFileOf(st).delete() } catch (_: Throwable) { } }
            else if (budgetCut && !interrupted) trackers.values.firstOrNull()?.let { storage.log("RECHECK: круг не закончен (проверено ${it.count} в этом круге), следующий запуск продолжит с этого места") }
            if (interrupted) storage.log("RECHECK: сервисы, список обхода и приоритеты не трогаю — перепроверка прервана")
            try {
                if (!interrupted && !userStopped && !budgetCut && budgetMs - (System.currentTimeMillis() - started) > 0) {
                    RecheckState.progress.value = "Проверяю сервисы у рабочих конфигов…"
                    AutoServices.run(context, storage)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                storage.log("SVC: ошибка автопроверки сервисов: ${e.message}")
            }
            try {
                if (!interrupted && !StopRequest.requestedSince(started)) DirectProbe.run(context, storage, onlyDue = true)
            } catch (e: Exception) {
                storage.log("BYPASS: ошибка проверки списка обхода: ${e.message}")
            }
            try {
                if (interrupted) {
                    // приоритеты по прерванному итогу не меняем
                } else if (RecheckGuard.suspicious(alive.get(), if (userStopped) checked.coerceAtLeast(1) else totalCount) || RecheckGuard.networkChanged(netBefore, NetInfo.label(context))) {
                    storage.log("PRIO: приоритеты не меняю — итог перепроверки ненадёжен (живых ${alive.get()} из $totalCount, сеть было $netBefore, стало ${NetInfo.label(context)})")
                } else AutoPriority.update(storage)
            } catch (e: Exception) {
                storage.log("PRIO: ошибка авто-приоритетов: ${e.message}")
            }
            val seconds = (System.currentTimeMillis() - started) / 1000
            val stoppedByUser = userStopped || StopRequest.requestedSince(started)
            val summary = "проверено $checked из $totalCount, живых ${alive.get()}, за $seconds с" + (if (abortNote.isNotEmpty()) " (прервана: $abortNote)" else "") + (if (stoppedByUser) " (остановлена вами, результаты сохранены)" else "")
            storage.log("RECHECK: $summary")
            LocalSocks.statsLine()?.let { storage.log(it) }
            RecheckState.progress.value = "Перепроверка завершена: $summary"
            PlanBus.notifyChanged()
            try {
                // после прерывания по отсутствию интернета цели не перепроверяем (повторится при появлении сети); после смены сети или режима — можно
                if (!stoppedByUser && (!interrupted || guard.stale(Storage(context).mode, NetInfo.label(context)))) TargetsWorker.enqueueAfterRefresh(context) // свежие проверки — повод обновить серверы целей
            } catch (_: Exception) {
            }
            return summary
        } finally {
            RecheckState.running.value = false; RecheckState.detail.value = null
        }
    }

    /** Только приложения (сервисы) у уже рабочих конфигов: без TCP-отсева и без полной перепроверки всех конфигов. */
    suspend fun runServices(context: Context, mode: Mode, reason: String = MANUAL): String {
        if (!XrayCore.available) return "ядро недоступно"
        if (!RecheckState.running.compareAndSet(false, true)) return "перепроверка уже идёт"
        val storage = Storage(context, mode)
        val startedSvc = System.currentTimeMillis()
        try {
            if (NetInfo.kind(context) == NetInfo.Kind.NONE || FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())) {
                storage.log("SVC: перепроверка приложений пропущена ($reason) — у телефона нет интернета")
                return "нет сети"
            }
            RecheckState.progress.value = "Проверяю приложения у рабочих конфигов…"
            storage.log("SVC: перепроверка только приложений ($reason)")
            val n = AutoServices.run(context, storage, limit = 60, all = true)
            val text = if (n == 0) "Нет рабочих конфигов для проверки приложений (сначала «Перепроверить всё»)" else "Приложения проверены у $n конфигов"
            RecheckState.progress.value = text
            PlanBus.notifyChanged()
            try {
                if (!StopRequest.requestedSince(startedSvc)) TargetsWorker.enqueue(context)
            } catch (_: Exception) {
            }
            return text
        } finally {
            RecheckState.running.value = false; RecheckState.detail.value = null
        }
    }

    fun enqueueServices(context: Context, mode: Mode) {
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(RecheckWorker.KEY_MODE to mode.name, RecheckWorker.KEY_ONLY_SERVICES to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        RecheckState.progress.value = "Проверка приложений запущена…"
        WorkManager.getInstance(context).enqueueUniqueWork("phoenix_recheck", ExistingWorkPolicy.KEEP, request)
    }

    /** «Только Wi-Fi» — требование, которое проверяет сама перепроверка при старте. Раньше это было ограничение WorkManager
     *  (UNMETERED/CONNECTED), и когда включался VPN, сеть по ограничению «менялась», работа останавливалась и начиналась заново
     *  (журнал 22.11: подключение во время перепроверки сбрасывало её без единой строки в журнале). */
    private fun wifiOnly(context: Context, manual: Boolean): Boolean {
        val st = AppSettings(context)
        return WifiOnlyPolicy.effective(st.wifiOnly, manual, st.lastSuccessAt, System.currentTimeMillis())
    }

    private fun constraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()

    /** Запустить в фоне (переживает закрытие экрана). Повторный запуск, пока идёт прежний, ничего не делает. */
    fun enqueue(context: Context, mode: Mode, withForeign: Boolean = false, reason: String = MANUAL) {
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(RecheckWorker.KEY_MODE to mode.name, RecheckWorker.KEY_FOREIGN to withForeign, RecheckWorker.KEY_REASON to reason, RecheckWorker.KEY_WIFI_ONLY to wifiOnly(context, Recheck.isManual(reason))))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        RecheckState.progress.value = "Перепроверка запущена…"
        WorkManager.getInstance(context).enqueueUniqueWork("phoenix_recheck", ExistingWorkPolicy.KEEP, request)
    }

    /** Продолжение круга после части без службы переднего плана: [part] — номер части (не больше [MAX_CONTINUATIONS]). */
    fun enqueueContinuation(context: Context, mode: Mode, withForeign: Boolean, part: Int) {
        if (part > MAX_CONTINUATIONS) return
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setConstraints(constraints())
            .setInitialDelay(15, java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf(RecheckWorker.KEY_MODE to mode.name, RecheckWorker.KEY_FOREIGN to withForeign, RecheckWorker.KEY_REASON to "$CONTINUE_REASON $part", RecheckWorker.KEY_WIFI_ONLY to wifiOnly(context, false)))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("phoenix_recheck_cont", ExistingWorkPolicy.REPLACE, request)
    }

    /** Повтор прерванной перепроверки через [RETRY_DELAY_SEC] секунд (один раз: у повтора причина «повтор»). */
    fun enqueueRetry(context: Context, mode: Mode) {
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setConstraints(constraints())
            .setInitialDelay(RETRY_DELAY_SEC, java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf(RecheckWorker.KEY_MODE to mode.name, RecheckWorker.KEY_FOREIGN to false, RecheckWorker.KEY_REASON to "повтор", RecheckWorker.KEY_WIFI_ONLY to wifiOnly(context, false)))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("phoenix_recheck_retry", ExistingWorkPolicy.REPLACE, request)
    }

    /** Сам запускать, когда запасной не нашёлся: не чаще раза в [MIN_GAP_MS] и только если это не отключено в настройках. */
    fun enqueueIfDue(context: Context, mode: Mode, reason: String = "авто", urgent: Boolean = false): Boolean {
        val settings = AppSettings(context)
        if (!settings.autoRecheck || RecheckState.running.value) return false
        if (!urgent && NetFlap.busy(System.currentTimeMillis())) return false // сеть меняется часто — подождать
        // тяжёлое делаем на зарядке; срочное (запасной не нашёлся) — сразу
        if (!urgent && !PowerInfo.heavyAllowed(context, settings.lastRecheckAt)) return false
        if (System.currentTimeMillis() - settings.lastRecheckAt < MIN_GAP_MS) return false
        enqueue(context, mode, withForeign = mode == Mode.BLACKLIST && !LiteProfile.ON, reason = reason) // авто: в чёрных списках — и конфиги белых (как раньше)
        return true
    }
}

class RecheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var mode = Mode.entries.firstOrNull { it.name == inputData.getString(KEY_MODE) } ?: Storage(applicationContext).mode
        // автоматическая перепроверка не тратит больше суточного лимита по мобильной сети; ручная и поиск сервера при обрыве не ограничиваются
        val autoRun = !inputData.getBoolean(KEY_ONLY_SERVICES, false) && !Recheck.isManual(inputData.getString(KEY_REASON) ?: Recheck.MANUAL)
        if (autoRun && BackgroundBudgetGate.exhausted(applicationContext)) return Result.success()
        // условия запуска (раньше — ограничения WorkManager): нет сети или нужен Wi-Fi, а сейчас мобильная — подождать и повторить
        val kind = NetInfo.kind(applicationContext)
        if (kind == NetInfo.Kind.NONE || (inputData.getBoolean(KEY_WIFI_ONLY, false) && kind == NetInfo.Kind.MOBILE)) return Result.retry()
        // перепроверка (и ручная тоже) не идёт в режиме «по умолчанию»: сначала определяем тип блокировок в этой сети
        if (ModeFirst.ensure(applicationContext)) mode = Storage(applicationContext).mode
        try {
            if (inputData.getBoolean(KEY_ONLY_SERVICES, false)) {
                Recheck.runServices(applicationContext, mode)
                return Result.success()
            }
            // Н1: служба переднего плана снимает лимит фоновой задачи (10 минут): раньше система обрывала перепроверку на 10-й минуте
            val reason = inputData.getString(KEY_REASON) ?: Recheck.MANUAL
            val fg = try {
                setForeground(getForegroundInfo())
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) { // на Android 12+ из фона: ForegroundServiceStartNotAllowedException (API 31) — это IllegalStateException
                Storage(applicationContext).log("RECHECK: без службы переднего плана (${e.javaClass.simpleName}) — иду частями по ${Recheck.NO_FOREGROUND_BUDGET_MS / 60_000} мин")
                false
            } catch (e: SecurityException) {
                Storage(applicationContext).log("RECHECK: без службы переднего плана (${e.javaClass.simpleName}) — иду частями по ${Recheck.NO_FOREGROUND_BUDGET_MS / 60_000} мин")
                false
            }
            val withForeign = inputData.getBoolean(KEY_FOREIGN, false)
            Recheck.runAll(applicationContext, mode, budgetMs = if (fg) Recheck.BUDGET_MS else Recheck.NO_FOREGROUND_BUDGET_MS, withForeign = withForeign, reason = reason)
            if (!fg && RecheckState.roundOpen) {
                val part = (reason.removePrefix(Recheck.CONTINUE_REASON).trim().toIntOrNull() ?: 0) + 1
                Recheck.enqueueContinuation(applicationContext, mode, withForeign, part)
            }
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // систему или WorkManager остановили работу: без этой строки перепроверка пропадала бесследно
            val why = if (android.os.Build.VERSION.SDK_INT >= 31) "причина ${stopReason}" else "причина неизвестна"
            try { Storage(applicationContext).log("RECHECK: работа остановлена системой ($why) — сеть ${NetInfo.label(applicationContext)}") } catch (_: Throwable) {}
            throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotifChannels.ensure(applicationContext, CHANNEL_ID, tr("Phoenix: перепроверка"), NotificationManager.IMPORTANCE_LOW)
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Phoenix")
            .setContentText(tr("Перепроверяю конфиги…"))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_ONLY_SERVICES = "only_services"
        const val KEY_FOREIGN = "foreign"
        const val KEY_REASON = "reason"
        const val KEY_WIFI_ONLY = "wifi_only"
        private const val CHANNEL_ID = "phoenix_recheck"
        // другое число, чем у уведомления VPN (4712): иначе на Android 10-11 срочная задача перепроверки
        // подменяет уведомление VPN своим, и кнопки «Отключить»/«Сменить сервер» пропадают до смены статуса
        private const val NOTIFICATION_ID = 4713
    }
}
