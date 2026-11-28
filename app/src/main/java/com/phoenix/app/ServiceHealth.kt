package com.phoenix.app

// Здоровье туннеля: проверки связи и решение о реакции (вынесено из PhoenixVpn.kt без изменения поведения).

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal suspend fun PhoenixVpnService.healthy(): Boolean = withContext(Dispatchers.IO) {
    energy("health")
    val port = healthPort
    if (port <= 0) return@withContext controller?.isRunning == true
    if (controller?.isRunning != true) return@withContext false
    val auth = healthAuth ?: return@withContext false
    // адреса опрашиваются одновременно, первый открывшийся решает дело (раньше по очереди: при мёртвом сервере одна проверка шла до 15 с, С3).
    // Таймауты прежние. Свой scope: блокирующие запросы остальных адресов не держат возврат, они заканчиваются сами
    val urls = PhoenixVpnService.HEALTH_URLS
    val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val answers = kotlinx.coroutines.channels.Channel<HealthAnswer>(urls.size)
    try {
        urls.forEachIndexed { i, url ->
            probes.launch {
                val host = url.substringAfter("://").substringBefore('/')
                val a = try {
                    LocalSocks.get(port, auth, url, connectTimeoutMs = 5_000, readTimeoutMs = 5_000).use { r ->
                        if (HealthVerdict.ok(url, r.code)) HealthAnswer(i, true, "") else HealthAnswer(i, false, "$host: HTTP ${r.code}")
                    }
                } catch (e: Exception) {
                    HealthAnswer(i, false, "$host: ${LocalSocks.describe(e)}")
                }
                answers.trySend(a)
            }
        }
        val got = ArrayList<HealthAnswer>()
        repeat(urls.size) {
            got += answers.receive()
            if (HealthSummary.ok(got)) {
                healthDetail = ""
                return@withContext true
            }
        }
        healthDetail = HealthSummary.detail(got)
        false
    } finally {
        probes.cancel()
    }
}

/** Проверка туннеля «одна за раз»: экран, смена сети и таймер в одну секунду не запускают три одинаковые проверки. */
internal suspend fun PhoenixVpnService.checkHealthOnce(force: Boolean = false) {
    if (!flight.tryEnter("health")) return
    statTick()
    try {
        checkHealth(force)
    } finally {
        flight.leave("health")
    }
}

internal suspend fun PhoenixVpnService.checkHealth(force: Boolean = false) {
    if (!wantRunning) return
    val genBefore = coreGen
    if (opLock.isLocked) {
        // идёт подключение, смена сервера или перезапуск ядра: проверка в этот момент ловит «ядро не работает»/ECONNREFUSED — это не отказ сервера (журнал 06.11 06:08)
        val now = System.currentTimeMillis()
        if (now - lastBusySkipLogAt > 60_000L) { lastBusySkipLogAt = now; storage.log("HEALTH: идёт смена сервера или перезапуск ядра — проверку пропускаю, результат был бы ложным") }
        return
    }
    if (healthy()) {
        if (failures > 0 || outage.active || VpnState.status.value.note.isNotEmpty()) {
            if (failures > 0) storage.log("HEALTH: связь через [$mainTitle] восстановилась (неудач было $failures), сеть ${NetInfo.label(this)}")
            // перерыв, открытый неудачным поиском сервера, кончился: считаем до возврата связи, а не до следующей смены (журнал 20.11: «1200 с», хотя связь возвращалась)
            closeOutage(DowntimeStats.LOSS)
            failures = 0
            outage.end()
            lastConnectedAt = System.currentTimeMillis()
            directAhead?.cancel(); directAhead = null
            if (VpnState.status.value.phase == VpnPhase.CONNECTED && isNoLinkNote(VpnState.status.value.note)) setConnected(foreignNote)
        }
        pinFailSince = 0L
        // раз в 10 минут запоминаем, что текущий сервер в этой сети работает (бесплатно: проверка и так идёт)
        val now = System.currentTimeMillis()
        if (now - lastOkRecordAt > 10 * 60_000L) {
            lastOkRecordAt = now
            recordHist(mainLink, true)
            rememberGood(mainLink)
        }
        diagGoogle("плановая проверка")
        return
    }
    if (coreGen != genBefore || opLock.isLocked) {
        // пока шла проверка, ядро перезапустили (смена сервера, режима, сети): отрицательный итог относится к прежнему ядру
        storage.log("HEALTH: ядро перезапускали во время проверки — результат отбрасываю")
        return
    }
    outage.fail(System.currentTimeMillis())
    failures++
    storage.log(
        "HEALTH: нет связи через [$mainTitle], неудача $failures, ядро ${if (controller?.isRunning == true) "работает" else "не работает"}, " +
            "сеть ${NetInfo.label(this)}, режим ${storage.mode.title}" + (if (homeLink != null) ", идёт сервер для приложения" else "") +
            (if (healthDetail.isNotEmpty()) ", причина: $healthDetail" else "")
    )
    if (hevActive && controller?.isRunning == true && !HevTunnel.isRunning()) {
        // hev остановился посреди сессии (память, система): вместо перебора серверов переходим на встроенный движок
        hevCrashed = true
        storage.log("VPN: hev-туннель остановился посреди сессии — перехожу на встроенный движок до конца сессии, сервер прежний [$mainTitle]")
        val ok = opLock.withLock {
            if (!wantRunning) false else try {
                withContext(Dispatchers.IO) { reloadCore(mainLink) }
                true
            } catch (e: Throwable) {
                storage.log("VPN: не удалось перейти на встроенный движок: ${e.message}")
                false
            }
        }
        if (ok) { failures = 0; outage.end(); delay(1_500); return }
    }
    if (failures == HealthGate.detectAt(NetState.recentShake(System.currentTimeMillis()))) {
        // отменяем прежний запуск (например, из onNetworkChanged) и запускаем новый определённо forced: иначе при
        // совпадении по времени прежний уже успевает выставить lastDetectAt, и этот сразу выходит по PhoenixVpnService.DETECT_GAP_MS,
        // ничего не определив — а первая проверка связи после смены сети и есть самый частый такой случай (находка Фейбла №3.1)
        detectJob?.cancel()
        detectJob = scope.launch { detectMode(force = true) }
    } // связь пропала: возможно, сменился тип блокировок
    val mobileNow = NetInfo.kind(this) == NetInfo.Kind.MOBILE
    // прямой замер заранее, в фоне (С3): к решающей неудаче он уже готов, а не ждёт ещё до 14 с; один на эпизод и сеть
    // замер заранее с истёкшим сроком (решение было «подождать», эпизод идёт дальше) отбрасываем, чтобы на этой неудаче запустился новый (Р2)
    if (directAhead != null && !HealthGate.aheadUsable(directAheadSince, directAheadEpoch, directAheadAt, outage.since, NetEpoch.id, System.currentTimeMillis())) {
        // cancel() отменяет только ожидание: блокирующий замер внутри SharedMeasure всё равно доработает (до ~35 с), и его результат получит тот, кто ждёт
        directAhead?.cancel(); directAhead = null
    }
    if (failures == HealthGate.measureAheadAt(mobileNow) && directAhead == null && NetInfo.kind(this) != NetInfo.Kind.NONE &&
        !FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())) {
        directAheadSince = outage.since
        directAheadEpoch = NetEpoch.id
        directAheadAt = System.currentTimeMillis()
        directAhead = scope.async(Dispatchers.IO) { BlockDetector.classify(ModeDetector.measureConfirmed(this@checkHealth)) == BlockType.OFFLINE }
    }
    if (failures < HealthGate.failuresNeeded(mobileNow)) {
        // Подтвердим быстрее: следующая проверка через несколько секунд.
        delay(4_000)
        checkHealth(force = true)
        return
    }
    storage.log("HEALTH: от первой неудачи до решения ${((System.currentTimeMillis() - outage.since).coerceAtLeast(0L)) / 1000} с")
    val kindNone = NetInfo.kind(this) == NetInfo.Kind.NONE
    val modeHeld = ModeHold.active(settings.manualModeAt, settings.manualModeNet, System.currentTimeMillis(), NetInfo.label(this))
    // решение принимает HealthDecision.decide (порядок условий проверяется тестами); дорогой прямой замер запрашивается им только когда нужен
    fun snapshot(direct: Boolean?) = HealthDecision.Snapshot(
        autoReconnect = settings.autoReconnect,
        noNetwork = kindNone,
        directOffline = direct,
        provisionalDoubt = { provisional.active && failures >= HealthGate.failuresNeeded(NetInfo.kind(this) == NetInfo.Kind.MOBILE) && doubtHint() },
        modePending = settings.autoMode && !modeHeld && FailoverGate.modePending(storage.mode, ModeDetector.lastType, ModeDetector.lastTypeAt, System.currentTimeMillis()),
        pinnedAndRunning = { pinned(mainLink) && controller?.isRunning == true },
        modeSettling = FailoverGate.modeSettling(modeSwitchedAt, System.currentTimeMillis()),
        netSettling = NetState.link(System.currentTimeMillis()) == NetState.Link.SETTLING,
        netFlapping = NetState.isFlapping(System.currentTimeMillis()),
        recentlyFailedOver = System.currentTimeMillis() < nextFailoverAt,
    )
    // система считает сеть живой, но напрямую (мимо VPN) не открывается ничего — значит, интернета нет, а не сервер плох
    // (в логе: три поиска по 2 минуты с нулём рабочих, пока у телефона не было интернета)
    var direct: Boolean? = if (FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())) true else null
    var action = HealthDecision.decide(snapshot(direct))
    if (action == HealthDecision.Action.MEASURE_DIRECT) {
        // готовый замер, начатый заранее в этом же эпизоде и этой же сети; иначе меряем сейчас, как раньше
        val ahead = directAhead?.takeIf { HealthGate.aheadUsable(directAheadSince, directAheadEpoch, directAheadAt, outage.since, NetEpoch.id, System.currentTimeMillis()) }
        directAhead = null
        direct = (try { ahead?.await() } catch (_: CancellationException) { null }
            ?: withContext(Dispatchers.IO) { BlockDetector.classify(ModeDetector.measureConfirmed(this@checkHealth)) == BlockType.OFFLINE })
            .also { if (it) ModeDetector.offlineAt = System.currentTimeMillis() }
        action = HealthDecision.decide(snapshot(direct))
    }
    when (action) {
        HealthDecision.Action.BROKEN_NO_RECONNECT -> {
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи через этот сервер"), link = mainLink, sub = VpnSub.BROKEN))
            return
        }
        HealthDecision.Action.NO_NETWORK -> {
            // нет сети вообще (самолёт, Wi-Fi без доступа) — перебирать серверы бессмысленно, только раскручивает ядро
            failures = 0
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи: у телефона нет сети"), link = mainLink, sub = VpnSub.NO_NETWORK))
            return
        }
        HealthDecision.Action.MEASURE_DIRECT -> return // недостижимо: после замера direct не null
        HealthDecision.Action.NO_INTERNET -> {
            failures = 0
            GovernedLimits.get(storage).let { g ->
                val n = NetInfo.label(this)
                if (g.outage(n, System.currentTimeMillis(), outage.since)) {
                    g.save()
                    storage.log("HEALTH: интернет у сети $n пропал сразу после тяжёлой проверки — запомнил, в этой сети буду делать меньше параллельных проверок")
                }
            }
            storage.log("HEALTH: прямая проверка тоже ничего не открыла (нет интернета у самой сети ${NetInfo.label(this)}) — серверы не перебираю, повторю при появлении сети")
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи: у сети нет интернета"), link = mainLink, sub = VpnSub.NO_NETWORK))
            return
        }
        HealthDecision.Action.DEFER_PROVISIONAL -> {
            failures = 0 // ждём ответа определения, серверы пока не перебираю
            return
        }
        HealthDecision.Action.DEFER_MODE_PENDING -> {
            failures = 0
            storage.log("HEALTH: определение показывает «${BlockDetector.title(ModeDetector.lastType!!)}», а режим пока «${storage.mode.title}» — жду подтверждения смены режима, серверы не перебираю")
            return
        }
        HealthDecision.Action.DEFER_PINNED -> {
            failures = 0
            storage.log("HEALTH: [$mainTitle] выбран вручную — не меняю сам (общая проверка может не проходить, например у telegram-only); сменить можно кнопкой «Сменить сервер»")
            if (pinFailSince == 0L) pinFailSince = System.currentTimeMillis()
            val minutes = (System.currentTimeMillis() - pinFailSince) / 60_000
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = "нет связи по общей проверке${if (minutes >= 3) " уже $minutes мин" else ""} — сервер выбран вручную, не меняю" + (if (minutes >= 3) tr("; если не работает, нажмите «Сменить сервер»") else ""), link = mainLink, sub = VpnSub.BROKEN))
            nextFailoverAt = System.currentTimeMillis() + 5 * 60_000L
            return
        }
        HealthDecision.Action.DEFER_MODE_SETTLING -> {
            failures = 0
            storage.log("HEALTH: режим только что сменился — даю серверу устояться, перебор отложен")
            return
        }
        HealthDecision.Action.DEFER_NET_SETTLING -> {
            failures = 0
            storage.log("HEALTH: сеть только что менялась или дёргалась (прямой замер ничего не открыл) — не виню сервер [$mainTitle] и не перебираю, проверю снова через минуту")
            return
        }
        HealthDecision.Action.DEFER_NET_FLAPPING -> {
            failures = 0
            storage.log("HEALTH: сеть недавно меняла тип несколько раз подряд — не виню сервер [$mainTitle] и не перебираю, проверю снова позже")
            return
        }
        HealthDecision.Action.BLAME_THEN_WAIT, HealthDecision.Action.BLAME_AND_FAILOVER -> {
            recordHist(mainLink, false) // сервер перестал работать именно в этой сети
            val early = HealthGate.earlyFall(System.currentTimeMillis(), mainChosenAt)
            if (early) storage.log("HEALTH: [$mainTitle] упал вскоре после выбора — не беру его снова час")
            Quarantine.add(ConfigIdentity.key(mainLink), networkAlive = networkUsable(), earlyFall = early) // 10 минут не выбираем его снова; третий провал за сутки — на сутки
            if (action == HealthDecision.Action.BLAME_THEN_WAIT) {
                failures = 0 // недавно уже перебирали и ничего не нашли — не мучаем сеть и батарею
                return
            }
        }
    }
    failover(who = "проверка связи")
}
