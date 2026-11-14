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
            RecheckState.running.value = false
            return "нет сети"
        }
        val netBefore = NetInfo.label(context)
        try {
            AppSettings(context).lastRecheckAt = started
            val groups = SearchPool.groups(context, storage, withForeign)
                .map { (st, list) -> st to list.filter { XrayConfig.supports(it.link) } }
            val totalCount = groups.sumOf { it.second.size }
            if (totalCount == 0) {
                RecheckState.progress.value = "Нет конфигов для проверки"
                return "нет конфигов"
            }
            storage.log(
                "RECHECK: старт ($reason), режим ${mode.title}, конфигов $totalCount" +
                    groups.joinToString("") { (st, l) -> ", ${if (st.mode == mode) "свои" else "из режима «${st.mode.title}»"} ${l.size}" } +
                    (if (withForeign) "" else " (только этот режим)")
            )
            val reachCache = ConcurrentHashMap<String, String>()
            val done = AtomicInteger()
            val alive = AtomicInteger()
            val guard = RunGuard(mode, NetInfo.label(context))
            var abortNote = ""
            var checked = 0
            for ((st, list) in groups) {
                val left = budgetMs - (System.currentTimeMillis() - started)
                if (left <= 0 || list.isEmpty()) continue
                val sink = SearchSink(st, context)
                val gov = GovernedLimits.get(st)
                val govNet = NetInfo.label(context)
                gov.begin(govNet)
                val search = ServerSearch(
                    reach = { h, p ->
                        val key = "$h:$p"
                        val cached = reachCache[key]
                        if (cached != null) cached.ifEmpty { null }
                        else (ServerHosts.reach(h, p, 2_500)).also { reachCache[key] = it ?: "" }
                    },
                    probe = { cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(context, cfg, url) } },
                    tcpParallel = ParallelGovernor.capFor(gov.tcp(govNet), NetInfo.kind(context) == NetInfo.Kind.MOBILE),
                    probeParallel = gov.probe(govNet, ColdStart.recheckParallel(CheckLimits.searchParallel(context), AppSettings(context).lastSuccessAt)),
                    maxCandidates = 20_000,
                    onePerHost = false,
                    loadSample = GatewayProbe.sampler(context),
                    shouldStop = { guard.stale(Storage(context).mode, NetInfo.label(context)) || RunGuard.hopeless(done.get(), alive.get()) },
                )
                search.find(list, want = Int.MAX_VALUE, budgetMs = left) { c, ms ->
                    sink.add(c, ms)
                    if (ms >= 0) alive.incrementAndGet()
                    val n = done.incrementAndGet()
                    if (n % 25 == 0) RecheckState.progress.value = "Перепроверка: $n из $totalCount, живых ${alive.get()}"
                    if (sink.pending >= 150) sink.flush()
                }
                sink.flush()
                val offlineNow = search.suspectedOffline
                st.log("RECHECK: итог по этапам — хостов проверено по TCP ${search.triedTcp} (не открылись ${search.tcpFailed}), запусков ядра ${search.probed}, ответов без запуска (ProbeShare) ${ProbeShare.saved}")
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
                if (search.aborted) {
                    abortNote = RunGuard.abortReason(guard.stale(Storage(context).mode, NetInfo.label(context)), done.get(), alive.get())
                    st.log("RECHECK: прервана — $abortNote")
                    break
                }
            }
            // прервана — результаты следующих этапов были бы ложными (журнал 05.11 11:01: после «прервана» — «SVC: … рабочих 0», «BYPASS: напрямую не открылось ничего»)
            val interrupted = abortNote.isNotEmpty()
            if (interrupted) storage.log("RECHECK: сервисы, список обхода и приоритеты не трогаю — перепроверка прервана")
            try {
                if (!interrupted && budgetMs - (System.currentTimeMillis() - started) > 0) {
                    RecheckState.progress.value = "Проверяю сервисы у рабочих конфигов…"
                    AutoServices.run(context, storage)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                storage.log("SVC: ошибка автопроверки сервисов: ${e.message}")
            }
            try {
                if (!interrupted) DirectProbe.run(context, storage, onlyDue = true)
            } catch (e: Exception) {
                storage.log("BYPASS: ошибка проверки списка обхода: ${e.message}")
            }
            try {
                if (interrupted) {
                    // приоритеты по прерванному итогу не меняем
                } else if (RecheckGuard.suspicious(alive.get(), totalCount) || RecheckGuard.networkChanged(netBefore, NetInfo.label(context))) {
                    storage.log("PRIO: приоритеты не меняю — итог перепроверки ненадёжен (живых ${alive.get()} из $totalCount, сеть было $netBefore, стало ${NetInfo.label(context)})")
                } else AutoPriority.update(storage)
            } catch (e: Exception) {
                storage.log("PRIO: ошибка авто-приоритетов: ${e.message}")
            }
            val seconds = (System.currentTimeMillis() - started) / 1000
            val summary = "проверено $checked из $totalCount, живых ${alive.get()}, за $seconds с" + (if (abortNote.isNotEmpty()) " (прервана: $abortNote)" else "")
            storage.log("RECHECK: $summary")
            LocalSocks.statsLine()?.let { storage.log(it) }
            RecheckState.progress.value = "Перепроверка завершена: $summary"
            PlanBus.notifyChanged()
            try {
                // после прерывания по отсутствию интернета цели не перепроверяем (повторится при появлении сети); после смены сети или режима — можно
                if (!interrupted || guard.stale(Storage(context).mode, NetInfo.label(context))) TargetsWorker.enqueue(context) // свежие проверки — повод обновить серверы целей
            } catch (_: Exception) {
            }
            return summary
        } finally {
            RecheckState.running.value = false
        }
    }

    /** Только приложения (сервисы) у уже рабочих конфигов: без TCP-отсева и без полной перепроверки всех конфигов. */
    suspend fun runServices(context: Context, mode: Mode, reason: String = "вручную"): String {
        if (!XrayCore.available) return "ядро недоступно"
        if (!RecheckState.running.compareAndSet(false, true)) return "перепроверка уже идёт"
        val storage = Storage(context, mode)
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
                TargetsWorker.enqueue(context)
            } catch (_: Exception) {
            }
            return text
        } finally {
            RecheckState.running.value = false
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

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Запустить в фоне (переживает закрытие экрана). Повторный запуск, пока идёт прежний, ничего не делает. */
    fun enqueue(context: Context, mode: Mode, withForeign: Boolean = false, reason: String = "вручную") {
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setConstraints(constraints())
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
