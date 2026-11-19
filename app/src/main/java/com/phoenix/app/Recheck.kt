package com.phoenix.app

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
}

/**
 * Откуда брать серверы, когда нужно найти рабочий. Берутся ВСЕ конфиги режима: пометка «когда-то не отвечал» не выбрасывает
 * конфиг (он мог не отвечать в другой сети), а лишь ставит его в очередь позже. В режиме чёрных списков дальше идут конфиги
 * белых списков: серверы белых списков работают и в обычной сети.
 */
object SearchPool {
    fun build(context: Context, storage: Storage, withForeign: Boolean = storage.mode == Mode.BLACKLIST): List<Candidate> {
        val own = poolOf(context, storage, storage.mode)
        if (!withForeign) return own
        val seen = own.map { it.identity }.toHashSet()
        val extra = Mode.entries.filter { it != storage.mode }
            .flatMap { poolOf(context, Storage(context, it), null).map { c -> c.copy(foreign = true) } }
            .filter { seen.add(it.identity) }
        return own + extra
    }

    /** Сначала свои конфиги режима, потом чужие — отдельными списками, чтобы результаты сохранялись в данные своего режима. */
    fun groups(context: Context, storage: Storage, withForeign: Boolean = storage.mode == Mode.BLACKLIST): List<Pair<Storage, List<Candidate>>> {
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
    const val BUDGET_MS = 25 * 60_000L
    const val MIN_GAP_MS = 30 * 60_000L

    suspend fun runAll(context: Context, mode: Mode, budgetMs: Long = BUDGET_MS, withForeign: Boolean = false, reason: String = "вручную"): String {
        if (!XrayCore.available) return "ядро недоступно"
        if (!RecheckState.running.compareAndSet(false, true)) return "перепроверка уже идёт"
        val started = System.currentTimeMillis()
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
            val groups = allGroups.map { (st, l) -> st to l.filterNot { RecheckSkip.quarantined(it) } }
            val totalCount = groups.sumOf { it.second.size }
            if (totalCount == 0) {
                RecheckState.progress.value = "Нет конфигов для проверки"
                return "нет конфигов"
            }
            storage.log(
                "RECHECK: старт ($reason), режим ${mode.title}, конфигов $totalCount" +
                    groups.joinToString("") { (st, l) -> ", ${if (st.mode == mode) "свои" else "из режима «${st.mode.title}»"} ${l.size}" } +
                    (if (withForeign) "" else " (только этот режим)") +
                    (if (quarantineSkipped > 0) "; пропущено $quarantineSkipped: недавно мёртвые и в этой сети подряд не работали" else "")
            )
            val reachCache = ConcurrentHashMap<String, String>()
            val done = AtomicInteger()
            val judged = AtomicInteger()
            val alive = AtomicInteger()
            val tcpDead = AtomicInteger()
            val skippedLive = AtomicInteger()
            var lastDetailAt = 0L
            val guard = RunGuard(mode, NetInfo.label(context))
            var abortNote = ""
            var userStopped = false
            var checked = 0
            for ((st, list) in groups) {
                val left = budgetMs - (System.currentTimeMillis() - started)
                if (left <= 0 || list.isEmpty()) continue
                val sink = SearchSink(st, context)
                val speed = AppSettings(context).recheckSpeed
                val probeBase = RecheckSpeed.probes(speed, CheckLimits.searchParallel(context))
                val gov = GovernedLimits.get(st)
                val govNet = NetInfo.label(context)
                gov.begin(govNet)
                // ручной запуск и «Быстрая» — выбор человека: уровень, накопленный из-за сбоев сети, урезает их слабее
                val lvlCap = when { speed == RecheckSpeed.FAST -> 0; reason == "вручную" -> 1; else -> ParallelGovernor.MAX_LEVEL }
                val detailTcp = minOf(RecheckSpeed.tcpCap(speed), ParallelGovernor.capFor(gov.tcp(govNet, lvlCap), NetInfo.kind(context) == NetInfo.Kind.MOBILE))
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
                    tcpParallel = minOf(RecheckSpeed.tcpCap(speed), ParallelGovernor.capFor(gov.tcp(govNet, lvlCap), NetInfo.kind(context) == NetInfo.Kind.MOBILE)),
                    probeParallel = gov.probe(govNet, ColdStart.recheckParallel(probeBase, AppSettings(context).lastSuccessAt), lvlCap),
                    maxCandidates = 20_000,
                    onePerHost = false,
                    loadSample = GatewayProbe.sampler(context),
                    shouldStop = { StopRequest.requestedSince(started) || guard.stale(Storage(context).mode, NetInfo.label(context)) || RunGuard.hopeless(judged.get(), alive.get()) },
                    offlineSuspect = ServerSearch.offlineLimit(NetInfo.kind(context) == NetInfo.Kind.MOBILE),
                    hostStrikes = RecheckSkip.HOST_STRIKES,
                )
                search.find(list, want = Int.MAX_VALUE, budgetMs = left) { c, ms ->
                    sink.add(c, ms)
                    if (ms >= 0) alive.incrementAndGet()
                    if (ms == ServerSearch.TCP_FAIL) tcpDead.incrementAndGet()
                    if (ms == ServerSearch.HOST_SKIPPED) skippedLive.incrementAndGet()
                    if (ms != ServerSearch.HOST_SKIPPED) judged.incrementAndGet()
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
                sink.flush()
                val offlineNow = search.suspectedOffline
                MemInfo.log(context, "перепроверка: конец")
                st.log("RECHECK: итог по этапам — хостов проверено по TCP ${search.triedTcp} (не открылись ${search.tcpFailed}), запусков ядра ${search.probed}, ответов без запуска (ProbeShare) ${ProbeShare.saved}, пропущено по адресу ${search.hostSkipped}")
                st.log("RECHECK: время по этапам — TCP в среднем ${if (search.triedTcp > 0) search.tcpMsTotal / search.triedTcp else 0} мс на хост, проба через ядро в среднем ${if (search.probed > 0) search.probeMsTotal / search.probed else 0} мс, притормаживал из-за нагрузки роутера ${search.throttled} раз, параллельно TCP ${ParallelGovernor.capFor(gov.tcp(govNet, lvlCap), NetInfo.kind(context) == NetInfo.Kind.MOBILE)}, проб ${gov.probe(govNet, probeBase, lvlCap)}, скорость ${speed}, уровень сети ${gov.level(govNet)}")
                if (offlineNow) {
                    // серверы подряд не открыли TCP — вероятнее всего, нет интернета у телефона, а не умерли серверы: ограничение не снимаем, а ужесточаем
                    if (gov.outage(govNet, System.currentTimeMillis())) st.log("RECHECK: сеть $govNet потеряла интернет во время перепроверки — в следующий раз параллельных проверок будет меньше")
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
                    abortNote = RunGuard.abortReason(guard.stale(Storage(context).mode, NetInfo.label(context)), judged.get(), alive.get())
                    st.log("RECHECK: прервана — $abortNote")
                    break
                }
            }
            // прервана — результаты следующих этапов были бы ложными (журнал 05.11 11:01: после «прервана» — «SVC: … рабочих 0», «BYPASS: напрямую не открылось ничего»)
            val interrupted = abortNote.isNotEmpty()
            if (interrupted) storage.log("RECHECK: сервисы, список обхода и приоритеты не трогаю — перепроверка прервана")
            try {
                if (!interrupted && !userStopped && budgetMs - (System.currentTimeMillis() - started) > 0) {
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
    suspend fun runServices(context: Context, mode: Mode, reason: String = "вручную"): String {
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
            .setConstraints(constraints(context, manual = true))
            .setInputData(workDataOf(RecheckWorker.KEY_MODE to mode.name, RecheckWorker.KEY_ONLY_SERVICES to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        RecheckState.progress.value = "Проверка приложений запущена…"
        WorkManager.getInstance(context).enqueueUniqueWork("phoenix_recheck", ExistingWorkPolicy.KEEP, request)
    }

    private fun constraints(context: Context, manual: Boolean): Constraints {
        val st = AppSettings(context)
        val wifi = WifiOnlyPolicy.effective(st.wifiOnly, manual, st.lastSuccessAt, System.currentTimeMillis())
        return Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    }

    /** Запустить в фоне (переживает закрытие экрана). Повторный запуск, пока идёт прежний, ничего не делает. */
    fun enqueue(context: Context, mode: Mode, withForeign: Boolean = false, reason: String = "вручную") {
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setConstraints(constraints(context, manual = reason == "вручную"))
            .setInputData(workDataOf(RecheckWorker.KEY_MODE to mode.name, RecheckWorker.KEY_FOREIGN to withForeign, RecheckWorker.KEY_REASON to reason))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        RecheckState.progress.value = "Перепроверка запущена…"
        WorkManager.getInstance(context).enqueueUniqueWork("phoenix_recheck", ExistingWorkPolicy.KEEP, request)
    }

    /** Сам запускать, когда запасной не нашёлся: не чаще раза в [MIN_GAP_MS] и только если это не отключено в настройках. */
    fun enqueueIfDue(context: Context, mode: Mode, reason: String = "авто", urgent: Boolean = false): Boolean {
        val settings = AppSettings(context)
        if (!settings.autoRecheck || RecheckState.running.value) return false
        if (!urgent && NetFlap.busy(System.currentTimeMillis())) return false // сеть меняется часто — подождать
        // тяжёлое делаем на зарядке; срочное (запасной не нашёлся) — сразу
        if (!urgent && !PowerInfo.heavyAllowed(context, settings.lastRecheckAt)) return false
        if (System.currentTimeMillis() - settings.lastRecheckAt < MIN_GAP_MS) return false
        enqueue(context, mode, withForeign = mode == Mode.BLACKLIST, reason = reason) // авто: в чёрных списках — и конфиги белых (как раньше)
        return true
    }
}

class RecheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mode = Mode.entries.firstOrNull { it.name == inputData.getString(KEY_MODE) } ?: Storage(applicationContext).mode
        // автоматическая перепроверка не тратит больше суточного лимита по мобильной сети; ручная и поиск сервера при обрыве не ограничиваются
        val autoRun = !inputData.getBoolean(KEY_ONLY_SERVICES, false) && (inputData.getString(KEY_REASON) ?: "вручную") != "вручную"
        if (autoRun && BackgroundBudgetGate.exhausted(applicationContext)) return Result.success()
        if (inputData.getBoolean(KEY_ONLY_SERVICES, false)) {
            Recheck.runServices(applicationContext, mode)
            return Result.success()
        }
        Recheck.runAll(applicationContext, mode, withForeign = inputData.getBoolean(KEY_FOREIGN, false), reason = inputData.getString(KEY_REASON) ?: "вручную")
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Phoenix: перепроверка"), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
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
        private const val CHANNEL_ID = "phoenix_recheck"
        // другое число, чем у уведомления VPN (4712): иначе на Android 10-11 срочная задача перепроверки
        // подменяет уведомление VPN своим, и кнопки «Отключить»/«Сменить сервер» пропадают до смены статуса
        private const val NOTIFICATION_ID = 4713
    }
}
