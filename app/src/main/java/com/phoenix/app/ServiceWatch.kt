package com.phoenix.app

// Фоновые наблюдатели: маршруты по часам, открытое приложение (вынесено из PhoenixVpn.kt без изменения поведения).

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

internal fun PhoenixVpnService.appTargets(): Map<String, Target> =
    activeTargets().filter { it.kind == TargetKind.APP && it.servers.isNotEmpty() && !it.isDirect }.associateBy { it.value }

/** Цели без приложений, которые сейчас идут мимо VPN (в обходе): им сервер не нужен, смена сервера при их открытии ничего не даёт. */
internal fun PhoenixVpnService.activeTargets(): List<Target> {
    val bypassed = appliedBypass
    TargetSyncIO.merge(this)
    return TargetStore(storage.targetsFile).load().filter { it.kind != TargetKind.APP || it.value !in bypassed }
}

/** Запоминает, в какие часы недели открывают приложения из целей (по данным об использовании). */
internal fun PhoenixVpnService.recordAppUse() {
    try {
        if (!ForegroundWatcher.hasAccess(this)) return
        val apps = appTargets()
        if (apps.isEmpty()) return
        val pkg = ForegroundWatcher.currentLong(this) ?: return
        if (pkg in apps) appHours.record(pkg)
    } catch (_: Throwable) {
    }
}

/** Раз в слот: приложения, которые обычно скоро откроют, — проверить их главный сервер; не отвечает — перепроверить цели заранее. */
internal suspend fun PhoenixVpnService.prepareApps() {
    if (!wantRunning || !settings.quietAuto || homeLink != null) return
    val slot = ActivityStore.get(this).slotNow()
    if (slot == lastAppPrepSlot) return
    lastAppPrepSlot = slot
    val apps = withContext(Dispatchers.IO) { appTargets() }
    if (apps.isEmpty()) return
    val soon = withContext(Dispatchers.IO) { appHours.soon(apps.keys) }
    for (pkg in soon) {
        val t = apps[pkg] ?: continue
        val link = t.servers.first()
        val alive = withContext(Dispatchers.IO) {
            try {
                val cfg = XrayConfig.probeConfig(link, ServerHosts.hostsForLink(link))
                ProbeShare.measure(cfg, t.checkUrl) { XrayCore.measureDelay(this@prepareApps, cfg, t.checkUrl, urgent = true) } >= 0 || SpeedRunner.reaches(this@prepareApps, link, t.checkUrl)
            } catch (_: Throwable) { false }
        }
        storage.log("ACT: «${t.label}» обычно открывают в этот час — сервер для него " + if (alive) "отвечает" else "не отвечает, запускаю перепроверку целей")
        if (!alive) TargetsWorker.enqueue(this)
    }
}

/** Проверка серверов, через которые идут цели по варианту А; при двух неудачах подряд цель получает следующий запасной сервер. */
internal suspend fun PhoenixVpnService.watchRoutes() {
    if (!wantRunning || activeRoutes.isEmpty() || homeLink != null) return
    val pm = getSystemService(PowerManager::class.java)
    if (pm != null && !pm.isInteractive) return
    // сеть, режим или ядро только что менялись: пробы идут по нестабильной сети, сбои не считаем
    if (System.currentTimeMillis() - disturbedAt < ROUTE_QUIET_MS) {
        routeFails.clear()
        return
    }
    val store = TargetStore(storage.targetsFile)
    val global = settings.globalPolicy
    val usageOk = ForegroundWatcher.hasAccess(this)
    var changed = false
    for (t in store.load()) {
        if (!wantRunning) return
        if (t.servers.isEmpty() || t.isDirect || t.resolve(global, finderOk, usageOk) != Policy.PROXY) continue
        val link = t.servers.first()
        if (ConfigIdentity.key(link) == ConfigIdentity.key(mainLink)) continue // идёт через основной сервер
        if (activeRoutes.none { it.link == link }) continue // маршрут для неё не действует (приложения нет на телефоне)
        suspend fun probeOnce(): Boolean = withContext(Dispatchers.IO) {
            val cfg = try {
                XrayConfig.probeConfig(link, ServerHosts.hostsForLink(link))
            } catch (_: UnsupportedLinkException) {
                return@withContext false
            }
            ProbeShare.measure(cfg, t.checkUrl) { XrayCore.measureDelay(this@watchRoutes, cfg, t.checkUrl, urgent = true) } >= 0 || SpeedRunner.reaches(this@watchRoutes, link, t.checkUrl)
        }
        // одиночный сбой не считаем: повторяем через несколько секунд
        val ok = probeOnce() || run { delay(5_000); probeOnce() }
        if (ok) {
            routeFails.remove(t.id)
            continue
        }
        val n = (routeFails[t.id] ?: 0) + 1
        routeFails[t.id] = n
        val name = LinkParser.parse(link)?.title?.let { urlDecode(it) }.orEmpty().ifBlank { "сервер" }
        storage.log("A: для «${t.label}» сервер [$name] не отвечает (${n} из 2, адрес проверки ${t.checkUrl}, запасных ${t.servers.size - 1})")
        if (n < 2) continue
        routeFails.remove(t.id)
        if (t.servers.size > 1) {
            // пробы занимают несколько секунд (повтор — ещё 5), а список серверов этой цели мог за это время
            // обновиться фоном (TargetsWorker) — ротация по снимку t, снятому в начале прохода, затёрла бы более
            // свежий список тем же порядком с [link] в конце (находка Фейбла №3.2); ротируем только если список,
            // по которому только что пробовали, всё ещё актуален
            val freshServers = store.load().firstOrNull { it.id == t.id }?.servers.orEmpty()
            if (freshServers.isNotEmpty() && freshServers.first() == link) {
                store.updateServers(t.id, freshServers.drop(1) + link, t.verifiedAt)
                storage.log("A: для «${t.label}» поставил следующий сервер, прежний ушёл в конец списка")
                changed = true
            } else {
                storage.log("A: для «${t.label}» список серверов уже обновился за время проверки — ротацию не делаю")
            }
        } else {
            storage.log("A: у «${t.label}» нет запасных серверов — запускаю перепроверку целей")
            TargetsWorker.enqueue(this)
        }
    }
    if (changed) PlanBus.notifyChanged()
}

/** Изменились цели или серверы — перезапустить ядро, только если маршруты действительно другие. */
internal suspend fun PhoenixVpnService.applyPlanIfChanged() = opLock.withLock {
    if (!wantRunning || tun == null) return@withLock
    val fresh = currentRoutes(mainLink)
    if (fresh == activeRoutes) return@withLock
    try {
        withContext(Dispatchers.IO) { reloadCore(mainLink) }
        storage.log("VPN: маршруты обновлены (${fresh.size})")
    } catch (e: Throwable) {
        storage.log("VPN: не удалось обновить маршруты: ${e.message}")
    }
}

internal fun PhoenixVpnService.hasSwitchTargets(): Boolean {
    val global = settings.globalPolicy
    return TargetStore(storage.targetsFile).load().any {
        it.kind == TargetKind.APP && it.servers.isNotEmpty() && !it.isDirect && it.resolve(global, finderOk, true) == Policy.SWITCH
    }
}

/** Вариант Б: открыто приложение, которому нужен свой сервер на весь VPN; когда из него ушли — возврат на прежний. */
internal suspend fun PhoenixVpnService.watchForeground() {
    // экран — самая дешёвая проверка, идёт первой; targets.tsv (hasSwitchTargets) читаем только если он правда нужен
    val pm = getSystemService(PowerManager::class.java)
    if (!wantRunning || (pm != null && !pm.isInteractive)) return
    if (!ForegroundWatcher.hasAccess(this) || !(switchTargetsCache ?: hasSwitchTargets().also { switchTargetsCache = it })) return
    val pkg = withContext(Dispatchers.IO) { ForegroundWatcher.current(this@watchForeground) }
    val now = System.currentTimeMillis()

    val target = if (pkg == null) null else PlanBuilder.switchFor(
        pkg, activeTargets(), settings.globalPolicy, mainLink, lastSwitchAt, now,
        finderOk = finderOk, usageOk = true,
    )
    if (target != null) {
        lastSwitchAt[target.id] = now
        opLock.withLock {
            if (!wantRunning) return@withLock
            val before = mainLink
            val beforeTitle = mainTitle
            var done = false
            for ((i, link) in target.servers.take(3).withIndex()) {
                val name = LinkParser.parse(link)?.title?.let { urlDecode(it) }.orEmpty().ifBlank { "сервер ${i + 1}" }
                val ok = try {
                    withContext(Dispatchers.IO) { reloadCore(link) }
                    tunnelWorks()
                } catch (e: Throwable) {
                    storage.log("B: для ${target.label} сервер [$name] не запустился: ${e.message}")
                    false
                }
                if (!ok) {
                    storage.log("B: для ${target.label} сервер [$name] не даёт связи — пробую следующий")
                    recordHist(link, false)
                    continue
                }
                if (homeLink == null) {
                    homeLink = before
                    homeTitle = beforeTitle
                }
                switchedPkg = target.value
                awaySince = 0L
                mainTitle = name
                setConnected("сервер для «${target.label}»")
                storage.log("VPN: переключился на сервер для ${target.label} [$name] (попытка ${i + 1} из ${minOf(3, target.servers.size)}, вернусь на [$beforeTitle] через минуту после ухода из приложения)")
                done = true
                break
            }
            if (!done && (mainLink != before || controller?.isRunning != true)) {
                try {
                    withContext(Dispatchers.IO) { reloadCore(before) }
                    mainTitle = beforeTitle
                    setConnected(foreignNote)
                } catch (e: Throwable) {
                    storage.log("B: не удалось вернуть прежний сервер: ${e.message}")
                }
            }
            if (!done) {
                storage.log("B: для ${target.label} ни один из первых серверов не работает — остался [$beforeTitle]; перепроверю цели")
                TargetsWorker.enqueue(this@watchForeground)
            }
        }
        return
    }

    // Возврат: пользователь ушёл из приложения и не возвращался минуту (диалоги разрешений и клавиатура возврат не запускают)
    val home = homeLink ?: return
    if (pkg == switchedPkg) {
        awaySince = 0L
        return
    }
    if (pkg != null && awaySince == 0L) awaySince = now
    if (awaySince == 0L || now - awaySince < PhoenixVpnService.AWAY_BEFORE_RETURN_MS) return
    opLock.withLock {
        if (!wantRunning) return@withLock
        try {
            withContext(Dispatchers.IO) { reloadCore(home) }
            mainTitle = homeTitle
            homeLink = null
            switchedPkg = ""
            awaySince = 0L
            setConnected("вернулся на основной сервер")
            storage.log("VPN: вернулся на основной сервер [$homeTitle]")
        } catch (e: Throwable) {
            storage.log("VPN: не удалось вернуться на основной сервер: ${e.message}")
        }
    }
}
