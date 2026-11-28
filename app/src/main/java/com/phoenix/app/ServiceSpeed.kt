package com.phoenix.app

// Низкая скорость: наблюдение, замер, смена сервера (вынесено из PhoenixVpn.kt без изменения поведения).

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

internal suspend fun PhoenixVpnService.watchSpeed() {
    if (!wantRunning || !settings.slowSwitch || controller?.isRunning != true || healthPort <= 0) return
    if (homeLink != null || failures > 0) return // идёт переключение для приложения или проверка связи
    if (pinned(mainLink)) return // сервер выбран вручную — по скорости его не меняем
    val pm = getSystemService(PowerManager::class.java)
    if (pm != null && !pm.isInteractive) return
    // «Мерить скорость и по мобильной сети» (по умолчанию выкл.) относится и к замерам службы, а не только к фоновым
    if (!settings.speedOnMetered && !NetInfo.isUnmetered(this)) return
    // суточный лимит мобильного трафика на фоновые проверки относится и к замерам службы (на Wi-Fi лимит не нужен)
    if (!NetInfo.isUnmetered(this) && withContext(Dispatchers.IO) { BackgroundBudgetGate.exhausted(this@watchSpeed) }) return
    if (withContext(Dispatchers.IO) { Focus.active(this@watchSpeed) }) { shape.reset(); return } // «Фокус»: открыто приложение из целей
    val rx = android.net.TrafficStats.getUidRxBytes(snapshotUid)
    val tx = android.net.TrafficStats.getUidTxBytes(snapshotUid)
    if (rx < 0 || tx < 0) return // система не отдаёт счётчики
    val prevRx = lastRx
    val prevTx = lastTx
    lastRx = rx
    lastTx = tx
    if (prevRx < 0) return
    val moved = (rx - prevRx) + (tx - prevTx)
    if (moved < PhoenixVpnService.ACTIVE_BYTES) {
        slow.reset() // телефон почти не используют: нечего измерять
        shape.reset()
        return
    }
    // реальный трафик и так идёт быстрее порога — сервер не медленный, замер не нужен
    val rate = SlowMonitor.passiveRateMbps(moved, PhoenixVpnService.SPEED_PERIOD_MS / 1000.0)
    slow.minMbps = settings.slowMinMbps.toDouble()
    // шейпинг оператора: скорость упала в 2–3 раза после нескольких минут нормальной; подтверждаем отдельным замером
    if (shape.feed(rate) && slow.canSwitch(System.currentTimeMillis())) {
        val peak = shape.peak
        val check = withContext(Dispatchers.IO) { probeSpeed() }
        resnapshotTraffic() // трафик самого замера не должен выглядеть на следующем шаге как работа пользователя
        if (check != null && check < peak / ShapingDetector.DROP_FACTOR) {
            storage.log("SLOW: скорость упала с ~${"%.1f".format(java.util.Locale.US, peak)} до ${"%.1f".format(java.util.Locale.US, check)} Мбит/с без смены нагрузки — похоже на шейпинг оператора, ищу другой сервер")
            shape.reset()
            slowFailover(check)
            return
        }
    }
    if (rate >= slow.minMbps * 2) {
        slow.reset()
        return
    }
    val mbps = withContext(Dispatchers.IO) { probeSpeed() }
    resnapshotTraffic()
    if (mbps == null) {
        // замер не удался — это не повод менять сервер; если связи нет совсем, это увидит проверка здоровья
        if (!speedProbeFailedLogged) storage.log("VPN: замер скорости не удался (файл не отдали) — сервер не меняю" + (speedErr.takeIf { it.isNotEmpty() }?.let { " [$it]" } ?: "") + ", сеть ${NetInfo.label(this)}")
        speedProbeFailedLogged = true
        return
    }
    speedProbeFailedLogged = false
    if (!slow.report(mbps, System.currentTimeMillis())) return
    slowFailover(mbps)
}

/**
 * Раз в 45 секунд, пока экраном пользуются: смотрим, сколько данных прошло через приложение (оно только и несёт туннель).
 * Если трафика почти нет — ничего не делаем и ничего не качаем. Если трафик есть, но медленный, делаем один небольшой
 * замер (до 400 КБ) и после нескольких медленных подряд пробуем другие серверы.
 */
internal fun PhoenixVpnService.resnapshotTraffic() {
    val rx = android.net.TrafficStats.getUidRxBytes(snapshotUid)
    val tx = android.net.TrafficStats.getUidTxBytes(snapshotUid)
    if (rx >= 0 && tx >= 0) { lastRx = rx; lastTx = tx }
}

/** Сеть годится, чтобы винить сервер: она есть, не за порталом входа и прямая проверка недавно не показывала «нет сети».
 * NET_CAPABILITY_VALIDATED не используется: при белых списках системная проверка Android (gstatic) может не проходить вовсе. */
internal fun PhoenixVpnService.networkUsable(): Boolean {
    if (NetInfo.kind(this) == NetInfo.Kind.NONE) return false
    if (FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())) return false
    return try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        cm.allNetworks.none { n ->
            cm.getNetworkCapabilities(n)?.let { c ->
                !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            } == true
        }
    } catch (_: Throwable) { true }
}

/**
 * Пропускает ли сервер IPv6: запрос к адресу Cloudflare по IPv6 через внутренний SOCKS ядра.
 * @return true/false, либо null — вывод сделать нельзя (нет и IPv4: тогда виноват не IPv6)
 */
internal fun PhoenixVpnService.ipv6Verdict(): Boolean? {
    val port = healthPort
    if (port <= 0) return null
    val auth = healthAuth ?: return null
    fun reaches(url: String): Boolean = try {
        LocalSocks.get(port, auth, url).use { it.code > 0 }
    } catch (_: Exception) {
        false
    }
    if (!reaches("http://1.1.1.1/")) return null
    return reaches("http://[2606:4700:4700::1111]/")
}

/**
 * Небольшой замер скорости через текущий туннель (по внутреннему SOCKS ядра).
 * @return Мбит/с, либо null — замер не удался (файл не отдали, обрыв): это не «медленно», а «неизвестно».
 */
internal fun PhoenixVpnService.probeSpeed(): Double? {
    energy("speed")
    val port = healthPort
    if (port <= 0) return null
    val auth = healthAuth ?: return null
    speedErr = ""
    for (url in PhoenixVpnService.SPEED_URLS) {
        try {
            val r = SpeedTest.measure({
                val resp = LocalSocks.get(port, auth, url, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
                if (resp.code !in 200..299) {
                    resp.close()
                    throw java.io.IOException("HTTP ${resp.code}")
                }
                object : java.io.FilterInputStream(resp.stream()) {
                    override fun close() {
                        resp.close()
                    }
                }
            }, maxMs = 4_000, maxBytes = 400_000)
            if (r != null) return r.mbps
            speedErr = "${url.substringAfter("://").substringBefore('/')}: мало данных"
        } catch (e: Exception) {
            speedErr = "${url.substringAfter("://").substringBefore('/')}: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    return null
}

/**
 * Текущий сервер медленный: параллельно ищем несколько живых (не трогая работающее ядро), у каждого меряем скорость
 * небольшим файлом и переключаемся, только если лучший заметно быстрее; иначе остаёмся на прежнем.
 */
internal suspend fun PhoenixVpnService.slowFailover(oldMbps: Double) = opLock.withLock {
    if (!wantRunning || homeLink != null) return@withLock
    val origTitle = mainTitle
    val origLink = mainLink
    recordHist(origLink, false, oldMbps)
    storage.log("VPN: скорость через [$origTitle] ${"%.2f".format(java.util.Locale.US, oldMbps)} Мбит/с — ищу сервер быстрее")
    val rows = ConfigRepository.load(storage)
    val currentId = ConfigIdentity.key(origLink)
    val currentHost = ConfigIdentity.host(origLink)
    val ordered = SearchPool.build(this, storage)
        .filter { XrayConfig.supports(it.link) && it.identity != currentId && (it.host.isEmpty() || it.host != currentHost) }
    val sink = SearchSink(storage, this)
    VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("скорость мала — ищу сервер быстрее…"), link = mainLink, sub = VpnSub.SEARCHING))
    val found = withContext(Dispatchers.IO) { searchFor(ordered, want = 4, budgetMs = PhoenixVpnService.SLOW_SEARCH_BUDGET_MS, sink = sink) }
    sink.flush()
    val measured = ArrayList<Pair<Found, Double>>()
    for (f in found) {
        if (!wantRunning) return@withLock
        val r = withContext(Dispatchers.IO) { SpeedRunner.run(this@slowFailover, f.cand.link, maxMs = 4_000, maxBytes = 400_000) }
        if (r != null) energySpeedBytes(r.bytes)
        recordHist(f.cand.link, r != null && r.mbps >= slow.minMbps, r?.mbps ?: -1.0)
        val nm = rows.firstOrNull { it.raw == f.cand.link }?.displayName ?: f.cand.identity
        storage.log("SLOW: кандидат [$nm] задержка ${f.ms} мс, скорость ${r?.let { "%.2f".format(java.util.Locale.US, it.mbps) + " Мбит/с" } ?: "не измерена"}")
        if (r != null) measured += f to r.mbps
    }
    // лучшие по скорости первыми; подходят только заметно быстрее нынешнего
    val candidates = measured.sortedByDescending { it.second }.filter { SlowMonitor.betterEnough(it.second, oldMbps, slow.minMbps) }.take(2)
    var switched = false
    for ((f, mbps) in candidates) {
        val name = rows.firstOrNull { it.raw == f.cand.link }?.displayName ?: mainTitle
        val ok = try {
            withContext(Dispatchers.IO) { reloadCore(f.cand.link) }
            tunnelWorks()
        } catch (e: Throwable) {
            storage.log("SLOW: [$name] не запустился: ${e.message}")
            false
        }
        if (ok) {
            mainTitle = name
            storage.saveLastConnection(f.cand.link, mainTitle)
            setConnected("скорость была мала — сменил сервер (теперь ${"%.1f".format(java.util.Locale.US, mbps)} Мбит/с)")
            storage.log("VPN: из-за низкой скорости переключился на [$mainTitle] (${"%.2f".format(java.util.Locale.US, mbps)} Мбит/с, прежний $origTitle: ${"%.2f".format(java.util.Locale.US, oldMbps)})")
            switched = true
            break
        }
        // быстрый по замеру, но через туннель не работает: не оставляем, помечаем и пробуем следующий
        recordHist(f.cand.link, false)
        storage.log("SLOW: [$name] быстрый по замеру, но через туннель связи нет — отклонён")
    }
    if (!switched) {
        if (mainLink != origLink || controller?.isRunning != true) {
            try {
                withContext(Dispatchers.IO) { reloadCore(origLink) }
                mainTitle = origTitle
                storage.log("SLOW: вернул прежний сервер [$origTitle]")
            } catch (e: Throwable) {
                storage.log("SLOW: не удалось вернуть прежний сервер: ${e.message}")
            }
        }
        setConnected(foreignNote)
        storage.log("VPN: серверов заметно быстрее не нашлось (найдено живых ${found.size}, проверено ${sink.count}), остался [$origTitle]")
    }
    slow.switched(System.currentTimeMillis())
    lastRx = -1L
}
