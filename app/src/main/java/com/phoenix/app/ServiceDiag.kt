package com.phoenix.app

// Диагностика Google-сервисов через текущий сервер (вынесено из PhoenixVpn.kt без изменения поведения).

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

/**
 * Пока открыт Gemini или NotebookLM (приложение или сайт), раз в 2 минуты (и сразу после смены сервера) подробно проверяет через текущий сервер
 * сам сервис, вход в аккаунт и проверку связи Google; результат — строки `DIAG:`. Ничего не делает, если открыто другое.
 */
internal fun PhoenixVpnService.diagGoogle(reason: String) {
    if (diagRunning || !wantRunning) return
    val now = System.currentTimeMillis()
    val ctx = try { AppContext.now(this, activeTargets()) } catch (_: Throwable) { null }
    if (ctx == null || !GoogleDiag.relevant(ctx.pkg, ctx.site)) return
    // у открытого приложения свой маршрут (вариант А): его трафик идёт не через основной сервер — проверка через общий SOCKS
    // сказала бы про чужой сервер, а смена основного ничего бы не исправила
    val routeLink = ctx.target?.servers?.firstOrNull()?.takeIf { l -> l != Target.DIRECT && activeRoutes.any { it.link == l } }
    if (routeLink != null) {
        storage.log("DIAG: ${ctx.logLabel} идёт своим маршрутом — общий сервер не проверяю, маршрут сторожит проверка целей")
        return
    }
    val key = mainLink + "|" + connectedMode
    if (key == diagLastKey && !GoogleDiag.due(diagAt, now)) return
    diagLastKey = key
    diagAt = now
    diagRunning = true
    val title = mainTitle
    // снимок на момент запуска: проверка идёт десятки секунд, а сервер за это время мог смениться (вручную,
    // по другому сбою) — дальше действуем по тому серверу, который проверяли, а не по тому, что сейчас в mainLink
    val diagLink = mainLink
    val diagGen = coreGen
    scope.launch(Dispatchers.IO) {
        try {
            val port = LocalProxy.port
            val auth = LocalProxy.auth
            if (port <= 0 || auth == null) return@launch
            storage.log("DIAG: открыто ${ctx.logLabel}, сервер [$title], сеть ${NetInfo.label(this@diagGoogle)}, режим ${storage.mode.title} ($reason)")
            try {
                LocalSocks.get(port, auth, "https://www.cloudflare.com/cdn-cgi/trace", connectTimeoutMs = 6_000, readTimeoutMs = 6_000).use { r ->
                    val cc = GeoGate.parseLoc(r.head(2048))
                    storage.log("DIAG: страна выхода по Cloudflare: ${cc ?: "не определена"}")
                    // DIAG и так замеряет страну выхода этого сервера — раньше это оставалось только строкой в журнале,
                    // appAwareOrder же при выборе следующего сервера смотрит именно в ExitStore, а не в журнал
                    if (cc != null) ExitStore(storage.exitFile).put(CheckStore.keyOf(diagLink), cc, java.time.LocalDate.now().toString())
                }
            } catch (e: Throwable) {
                storage.log("DIAG: страна выхода не определена (${e.message})")
            }
            val closedBy = HashMap<String, Boolean>()
            for ((label, url) in GoogleDiag.urls) {
                val t0 = System.currentTimeMillis()
                val text = try {
                    LocalSocks.get(port, auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000, follow = true, maxRedirects = 8, userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").use { r ->
                        val head = r.head(65_536)
                        GoogleDiag.line(label, r.code, System.currentTimeMillis() - t0, r.hops, RegionBlock.closed(r, head).also { closedBy[label] = it }, null,
                            ("итог: " + GoogleDiag.finalHint(r.finalUrl) + "; " + PageSnippet.of(head, 120) + GoogleDiag.hintsIn(head).let { if (it.isNotEmpty()) " [признаки: $it]" else "" }).take(420))
                    }
                } catch (e: Throwable) {
                    GoogleDiag.line(label, 0, System.currentTimeMillis() - t0, emptyList(), false, e.message ?: e.javaClass.simpleName)
                }
                storage.log("DIAG: $text")
            }
            // расход на DIAG раньше нигде не считался (слепая зона EnergyMeter — находка Фейбла): пока открыт
            // Gemini/NotebookLM, это регулярный сетевой трафик каждые 2 минуты, и в «Автономность → расход» его не было видно
            energy("diag")
            // открытый сервис закрыт на этом сервере — исключаем сервер для него и ищем другой (не больше 3 раз за 30 минут)
            val want = GoogleDiag.labelFor(ctx.pkg, ctx.site)
            // страница Gemini отвечает 200 и из закрытых стран (та же причина, по которой фоновая проверка в ServiceCheck
            // судит о Gemini по NotebookLM, см. GoogleAi.needsOracle) — поэтому реальный признак закрытости даёт только
            // notebook, даже когда открыт именно Gemini; раньше здесь проверялось closedBy["gemini"], который почти
            // никогда не становится true, и ветка смены сервера просто не срабатывала (находка Фейбла №1)
            val closedKey = GoogleDiag.oracleKey(want)
            // у открытого приложения может не быть своей цели (например, сам NotebookLM без сайта/цели) — тогда
            // ctx.checkUrl == null, и раньше DIAG только писал «закрыт» в журнал и ничего не делал (находка №2);
            // подставляем адрес того же узла Google, который уже проверили выше
            val checkUrl = ctx.checkUrl ?: GoogleDiag.fallbackCheckUrl(ctx.pkg, ctx.site)
            // для самой проверки «а правда ли ещё закрыт новый кандидат» (verifyUrl в failover → stillBlocked) нужен тот же
            // надёжный узел — страница Gemini и тут отвечает 200 даже из закрытой страны, так что проверять надо notebook
            val verifyUrl = GoogleDiag.urls.firstOrNull { it.first == closedKey }?.second ?: checkUrl
            if (closedBy[closedKey] == true && checkUrl != null) {
                if (coreGen != diagGen) {
                    storage.log("DIAG: пока шла проверка, сервер уже сменился по другой причине — не исключаю и не переключаю")
                } else if (pinned(diagLink)) {
                    storage.log("DIAG: ${ctx.logLabel} закрыт на [$title], но сервер закреплён вручную — не снимаю закрепление сам")
                } else if (autoVeto.allow(System.currentTimeMillis())) {
                    markServiceClosed(diagLink, checkUrl, TargetStore(storage.targetsFile).load())
                    storage.log("DIAG: ${ctx.logLabel} закрыт на [$title] — исключаю сервер на 3 дня для этого сервиса и ищу другой (автоматически)")
                    smartVerdictText = "проверка Google показала, что ${ctx.label} здесь закрыт"
                    // проверяем сервис и на новом сервере, прежде чем считать смену удачной — иначе «3 смены за 30 мин» тратятся
                    // вслепую на серверы, для которых DIAG узнает о закрытости лишь через 2 минуты новым прогоном
                    // подбор идёт под то, что диагностировали, даже если пользователь за эти секунды переключился на другое приложение
                    // (в журнале: закрыт Notebook, а сервер искали уже под «Google») — иначе смена бесполезна для Notebook
                    diagCtx = ctx
                    try { failover(forced = true, smart = true, verifyUrl = verifyUrl, who = "проверка сервиса (DIAG)") } finally { diagCtx = null }
                } else {
                    storage.log("DIAG: ${ctx.logLabel} закрыт и на этом сервере, автоматические смены остановлены (3 за 30 мин) — похоже, подходящих выходов для этого сервиса сейчас нет")
                }
            } else if (closedBy[closedKey] == false) {
                autoVeto.reset()
            }
        } catch (_: Throwable) {
        } finally {
            diagRunning = false
        }
    }
}
