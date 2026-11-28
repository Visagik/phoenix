package com.phoenix.app

// Смена сервера: перебор, ручная и умная смена, возврат на прежний (вынесено из PhoenixVpn.kt без изменения поведения).

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
 * Учитывает, что открыто сейчас (приложение или сайт в браузере; если открыт сам Phoenix или рабочий стол — то, чем пользовались последние 20 минут):
 * серверы этой цели первыми, затем серверы других недавних приложений, затем остальные; где сервис не открывался — после них,
 * а серверы с выходом в закрытой для сервиса стране (по замеру) — в самом конце.
 */
internal fun PhoenixVpnService.appAwareOrder(list: List<Candidate>, force: Boolean = false): List<Candidate> {
    appFit = null
    if (!force && !settings.appAwareFailover) return list
    val targets = activeTargets()
    val ctx = diagCtx ?: try { AppContext.now(this, targets) } catch (_: Throwable) { null } ?: return list
    // без своей цели (находка Фейбла №2, тот же случай, что и в diagGoogle) приложение Gemini/NotebookLM иначе
    // вообще не участвует в этой упорядочке — DIAG мог бы отметить сервер запретным, но veto.active() здесь
    // никогда не сработает без адреса, по которому его искать
    val checkUrl = ctx.checkUrl ?: GoogleDiag.fallbackCheckUrl(ctx.pkg, ctx.site)
    if (checkUrl == null && ctx.others.isEmpty()) return list
    val primary = ctx.target?.realServers.orEmpty().map { ConfigIdentity.key(it) }.toSet()
    val others = ctx.others.flatMap { it.realServers }.map { ConfigIdentity.key(it) }.toSet()
    val svc = checkUrl?.let { ServiceMatch.serviceFor(it, storage.checkSelection().selected()) }
    val checks = CheckStore(storage.checksFile).load()
    val exits = ExitStore(storage.exitFile).load()
    val url = checkUrl.orEmpty()
    val blocked = { cc: String? -> checkUrl != null && AppFit.exitBlocked(url, svc, cc) }
    // сервер уже отмечен (вручную или DIAG) как закрытый именно для этого сайта — не предлагать его снова первым,
    // иначе пометка в ServiceVeto ничего не меняет в живом выборе (находили, но не чинили)
    val veto = ServiceVeto(storage.vetoFile)
    val vetoed = { c: Candidate -> checkUrl != null && veto.active(c.link, url) }
    val byService = ServiceMatch.order(list, checks, svc)
    val arranged = AppFit.arrange(byService, primary, others, { exits[CheckStore.keyOf(it.link)]?.cc }, blocked, vetoed)
    // «Auto» подписки: страна выхода плавает, для сервисов с проверкой региона (Gemini, ChatGPT…) такой сервер в самый конец (в журнале 07.11 смена закончилась на «Auto → Оптимальная»)
    val out = if (checkUrl != null && GeoGate.applies(checkUrl)) {
        val (auto, fixed) = arranged.partition { ServerKind.isFloatingExit(LinkParser.parse(it.link)?.title?.let { t -> urlDecode(t) }.orEmpty()) }
        // серверы белых списков (в чёрных они обычно в самом конце очереди) подмешиваем: после каждых двух своих один белый
        val (white, own) = fixed.partition { it.foreign || it.mismatch }
        if (storage.mode == Mode.BLACKLIST && white.isNotEmpty()) {
            storage.log("FAILOVER: для ${ctx.logLabel} закрыты многие обычные серверы — подмешиваю серверы белых списков (${white.size}) через каждые два своих")
            QueueMix.mix(own, white, 2) + auto
        } else fixed + auto
    } else arranged
    appFit = out.filter { c ->
        val t = AppFit.tier(c, primary, others, exits[CheckStore.keyOf(c.link)]?.cc, blocked, vetoed)
        t <= 1 || (t == 2 && svc != null && ServiceMatch.groupOf(c, checks, svc) <= 1) // без проверяемого сервиса «подходящими» не считаем все непроверенные серверы (было 1521 из 1677)
    }.map { it.identity }.toSet()
    val demoted = out.count { AppFit.tier(it, primary, others, exits[CheckStore.keyOf(it.link)]?.cc, blocked, vetoed) in 3..4 }
    val vetoedCount = out.count { AppFit.tier(it, primary, others, exits[CheckStore.keyOf(it.link)]?.cc, blocked, vetoed) == 5 }
    storage.log(
        "FAILOVER: открыто ${ctx.logLabel} — серверов цели ${primary.size}, других недавних приложений ${others.size}" +
            (if (svc != null) ", затем где открывается ${svc.id}" else "") +
            (if (demoted > 0) ", $demoted с закрытой для сервиса страной выхода — в самый конец" else "") +
            (if (vetoedCount > 0) ", $vetoedCount уже отмечены закрытыми именно для этого сайта — в самый конец" else "")
    )
    return out
}

/** Запоминает, что этот сервер работал в текущей сети и режиме: при следующей смене режима он пробуется первым. */
internal fun PhoenixVpnService.rememberGood(link: String) {
    try { LastGoodStore.of(java.io.File(storage.base, "lastgood.tsv")).remember(NetInfo.label(this), storage.mode.name, link, System.currentTimeMillis()) } catch (_: Throwable) { }
}

/**
 * Быстрые варианты проверяются сразу все, отдельными экземплярами ядра, не трогая туннель. Раньше они шли по одному с перезапуском
 * ядра на каждом (журнал 06.11: 34, 102, 171 и 51 с, всего 6 минут без результата).
 * @return варианты, ответившие на проверку, либо null, если предпроверка не удалась (тогда работает прежний перебор)
 */
/**
 * Быстрая проба сервера временным ядром (без перезапуска основного): TCP, затем один замер до gstatic. Блокирующая.
 * [fast] — без повтора по запасному адресу после таймаута: брошенная проба (предпроверка режима отменяет остальные, а замер в ядре Go прервать нельзя)
 * иначе дорабатывала бы двойной таймаут и держала место в лимите срочных проб.
 */
internal fun PhoenixVpnService.quickAlive(link: String, fast: Boolean = true): Boolean {
    val parsed = LinkParser.parse(link)
    var hosts: Map<String, String> = emptyMap()
    if (parsed?.host != null && parsed.port != null) {
        val ip = if (parsed.protocol == "hysteria2") ServerHosts.resolveOnly(parsed.host) else ServerHosts.reach(parsed.host, parsed.port, PhoenixVpnService.SEARCH_TCP_TIMEOUT_MS)
        if (ip == null) return false
        hosts = ServerHosts.hostsFor(parsed.host, ip)
    }
    val cfg = try { XrayConfig.probeConfig(link, hosts) } catch (_: UnsupportedLinkException) { return false }
    val ms = ProbeShare.measure(cfg, CheckServices.BASELINE_URL) { XrayCore.measureDelay(this@quickAlive, cfg, CheckServices.BASELINE_URL, urgent = true, fast = fast) }
    return ms >= 0
}

internal suspend fun PhoenixVpnService.prefilterPicks(picks: List<Pick>): List<Pick>? {
    if (picks.isEmpty()) return emptyList()
    return try {
        // отвечают по-разному: ждём первого рабочего и короткую паузу для остальных, не всех до конца (журнал 20.11: 34 с ожидания, потом всё равно разрыв)
        val found = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val firstFound = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = Semaphore(PhoenixVpnService.MODE_PREFILTER_PARALLEL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val jobs = picks.map { p ->
                scope.launch {
                    gate.withPermit {
                        if (quickAlive(p.link)) { found[p.link] = true; firstFound.complete(Unit) }
                    }
                }
            }
            scope.launch { jobs.forEach { it.join() }; firstFound.complete(Unit) }
            kotlinx.coroutines.withTimeoutOrNull(PhoenixVpnService.MODE_PREFILTER_MAX_MS) { firstFound.await() }
            if (found.isNotEmpty()) delay(PhoenixVpnService.MODE_PREFILTER_GRACE_MS)
        } finally {
            scope.cancel() // остальные пробы не ждём
        }
        picks.filter { found.containsKey(it.link) }
    } catch (e: Throwable) {
        storage.log("MODE: предпроверка быстрых вариантов не удалась (${e.message}) — иду по одному")
        null
    }
}

internal fun PhoenixVpnService.recordHist(link: String, ok: Boolean, mbps: Double = -1.0) {
    try {
        storage.history().record(ConfigIdentity.key(link), NetInfo.label(this), ok, mbps)
        ArmStore.of(java.io.File(storage.base, "bandit.tsv")).record(NetInfo.label(this), storage.mode.name, link, ok, System.currentTimeMillis())
    } catch (_: Throwable) {
    }
}

/**
 * Туннель не работает: ищем рабочий сервер среди ВСЕХ конфигов режима (а не среди нескольких лучших).
 * Поиск идёт, не трогая работающее ядро: сначала быстро TCP, потом настоящая задержка через отдельные экземпляры ядра,
 * параллельно (как в v2rayNG, но конвейером и с остановкой на первом найденном). Затем переключаемся на лучший найденный.
 */
/**
 * У3: свежий замер уже показывает другой режим, а сменить его ещё не успели (нужно два голоса) — меняем режим, а не ищем сервер.
 * Путь тот же, что у определения режима (ModeDetector.run → Mode.write, затем PlanBus.notifyChanged → applyModeIfChanged →
 * switchToModeServer «MODE: … пробую быстро найти рабочий»); второго пути смены режима нет. @return true — режим сменён, сервер не искать.
 */
internal fun PhoenixVpnService.switchModeInsteadOfServer(tag: String): Boolean {
    if (LiteProfile.ON) return false
    val now = System.currentTimeMillis()
    val held = ModeHold.active(settings.manualModeAt, settings.manualModeNet, now, NetInfo.label(this))
    val target = FailoverGate.modeInsteadOfServer(settings.autoMode, held, storage.mode, ModeDetector.lastType, ModeDetector.lastTypeAt, now) ?: return false
    val type = ModeDetector.lastType ?: return false
    val ago = (now - ModeDetector.lastTypeAt) / 1000
    storage.log("$tag: замер $ago с назад показал «${BlockDetector.title(type)}», а режим «${storage.mode.title}» — меняю режим, а не сервер")
    // голоса приводим к «подтверждено»: следующий замер того же типа не начнёт счёт заново
    synchronized(ModeDetector.voteLock) { ModeDecider.save(storage.voteFile, Vote(type, ModeDecider.NEED_IN_ROW)) }
    Mode.write(applicationContext.filesDir, target)
    PlanBus.notifyChanged()
    return true
}

/** Название открытого сейчас приложения из целей (или пусто) — для умной смены. */
internal fun PhoenixVpnService.openAppLabel(): String {
    val ctx = try { AppContext.now(this, activeTargets()) } catch (_: Throwable) { null } ?: return ""
    return ctx.label
}

/**
 * «Умная смена»: не ждёт отказа по таймеру, а сама разбирается. Принудительно проверяет сеть напрямую и туннель, и если
 * проблема в сервере (или вы просите лучший) — подбирает сервер под открытое сейчас приложение. Если нет сети или у сети
 * нет интернета, сервер не меняет и говорит об этом.
 */
internal suspend fun PhoenixVpnService.smartSwitch(who: String = "умная смена (кнопка)") {
    if (switchModeInsteadOfServer("SMART")) return
    val app = openAppLabel()
    val net = NetInfo.label(this)
    storage.log("SMART: умная смена, открыто: ${app.ifBlank { "неизвестно" }}, сеть $net, режим ${storage.mode.title}")
    VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("проверяю сеть и туннель…"), link = mainLink))
    val (none, offline, works) = quickNetState()
    val v = SmartVerdict.decide(none, offline, works, app)
    storage.log("SMART: вывод: ${v.text}")
    if (!v.switchServer) {
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("умная смена: {}", v.text), link = mainLink))
        showForeground(mainTitle.ifBlank { "Phoenix" }, tr("Умная смена: {}", v.text))
        return
    }
    smartVerdictText = v.text
    // Gemini/NotebookLM: страна выхода решает всё, а прежняя «умная смена» этого не учитывала (журнал 14.11, 18:55): текущий сервер
    // [🤖 AI] открывал NotebookLM, но смена всё равно ушла на Польшу, где он закрыт, потому что новый сервер под этот сервис не проверялся
    val targets = activeTargets()
    val ctx = try { AppContext.now(this, targets) } catch (_: Throwable) { null }
    var verifyUrl: String? = null
    var pinCtx: AppContext.Now? = null
    if (ctx != null && GoogleDiag.relevant(ctx.pkg, ctx.site)) {
        val oracle = GoogleDiag.urls.firstOrNull { it.first == GoogleDiag.oracleKey(GoogleDiag.labelFor(ctx.pkg, ctx.site)) }?.second
        val closed = if (oracle != null) withContext(Dispatchers.IO) { oracleClosed(oracle) } else null
        if (closed == false) {
            storage.log("SMART: ${ctx.logLabel} на этом сервере открывается — менять сервер не нужно")
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("умная смена: {} здесь открывается, сервер оставлен", ctx.label), link = mainLink))
            showForeground(mainTitle.ifBlank { "Phoenix" }, tr("{} здесь открывается — сервер оставлен", ctx.label))
            return
        }
        verifyUrl = oracle
        pinCtx = ctx
        if (closed == true) {
            val checkUrl = ctx.checkUrl ?: GoogleDiag.fallbackCheckUrl(ctx.pkg, ctx.site)
            if (checkUrl != null) {
                markServiceClosed(mainLink, checkUrl, targets)
                storage.log("SMART: ${ctx.logLabel} закрыт на [$mainTitle] — исключаю сервер на 3 дня для этого сервиса")
            }
        }
    }
    diagCtx = pinCtx
    try { failover(forced = true, smart = true, verifyUrl = verifyUrl, serverFailed = v.kind == SmartVerdict.Kind.SERVER_DEAD, who = who) } finally { diagCtx = null }
}

/** Открывается ли страница сервиса через туннель: true — закрыта по региону, false — открыта, null — проверить не удалось. */
internal fun PhoenixVpnService.oracleClosed(url: String): Boolean? {
    val port = LocalProxy.port
    val auth = LocalProxy.auth ?: return null
    if (port <= 0) return null
    return try {
        LocalSocks.get(port, auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000, follow = true, maxRedirects = 8,
            userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").use { r -> RegionBlock.closed(r, r.head(65_536)) }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Общая часть ручной кнопки «не открывается» и автоматического DIAG: сервер исключается для сайта на 3 дня (`ServiceVeto`),
 * выбранному сервису у этого сервера в `CheckStore` ставится «не открылся» (чтобы и фоновые проверки это видели),
 * и сервер убирается из списка серверов цели (если был там). Раньше это делала только ручная кнопка — у автоматического
 * DIAG была только пометка в ServiceVeto, которую живой выбор следующего сервера (appAwareOrder) тогда ещё не читал.
 */
internal fun PhoenixVpnService.markServiceClosed(link: String, url: String, targets: List<Target>) {
    ServiceVeto(storage.vetoFile).add(link, url)
    val key = CheckStore.keyOf(link)
    val services = storage.checkSelection().selected()
    val svc = ServiceMatch.serviceFor(url, services)
    val store = CheckStore(storage.checksFile)
    if (svc != null) store.modify(recordOdds = false) { cur ->
        cur[key]?.let { old -> mapOf(key to old.copy(services = old.services + (svc.id to CheckServices.FAILED))) } ?: emptyMap()
    }
    // цель того же сайта не должна держать этот сервер среди своих; чужие цели не трогаем, список берём свежий из файла, а не переданный снимок
    val id = ConfigIdentity.key(link)
    val fresh = TargetStore(storage.targetsFile).load()
    val hit = TargetSite.affected(fresh, link, url)
    for (t in hit) {
        TargetStore(storage.targetsFile).updateServers(t.id, t.servers.filter { it == Target.DIRECT || ConfigIdentity.key(it) != id }, t.verifiedAt)
    }
    if (hit.isNotEmpty()) storage.log("VETO: сервер убран у целей этого сайта: ${hit.joinToString { it.label }}")
}

internal suspend fun PhoenixVpnService.fixCurrent() {
    VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("разбираюсь, что не работает…"), link = mainLink))
    val (none, offline, works) = quickNetState()
    val ctx = if (works) { try { AppContext.now(this, activeTargets()) } catch (_: Throwable) { null } } else null
    if (works && ctx?.checkUrl != null) {
        storage.log("FIX: туннель жив, открыт ${ctx.logLabel} — считаю, что не работает сервис")
        vetoCurrent()
    } else {
        storage.log("FIX: сеть/туннель ${if (works) "в порядке, сервис не определён" else "не в порядке"} — умная смена")
        smartSwitch("кнопка «Не работает»")
    }
}

/**
 * Кнопка «Сервис не открывается на этом сервере»: запоминает, что на текущем сервере открытый сейчас сайт или приложение не работает
 * (приложение само этого не поняло — страница «не поддерживается в вашей стране» приходит с кодом 200), и подбирает другой сервер.
 */
internal suspend fun PhoenixVpnService.vetoCurrent() {
    val targets = activeTargets()
    val ctx = try { AppContext.now(this, targets) } catch (_: Throwable) { null }
    val url = ctx?.checkUrl
    if (ctx == null || url == null) {
        storage.log("VETO: не знаю, какой сервис открыт (нужен доступ к статистике использования или служба «сайт в браузере») — сервер не помечаю")
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("не знаю, какой сервис открыт — откройте его и нажмите снова"), link = mainLink))
        return
    }
    // у открытого приложения свой маршрут (вариант А): его трафик идёт через сервер цели, а не через основной. Помечать и менять основной
    // бессмысленно (журнал: «Notebook идёт своим маршрутом», а кнопка «Не работает» меняла основной сервер раз за разом) — закрытым
    // для сайта помечается сервер маршрута, цель берёт следующий из своего списка, а при пустом списке запускается подбор
    val tgt = ctx.target
    val routeLink = tgt?.servers?.firstOrNull()?.takeIf { l -> l != Target.DIRECT && activeRoutes.any { it.link == l } }
    if (tgt != null && routeLink != null) {
        val name = LinkParser.parse(routeLink)?.title?.let { urlDecode(it) }.orEmpty().ifBlank { "сервер" }
        storage.log("VETO: ${ctx.logLabel} идёт своим маршрутом через [$name] — помечаю закрытым для сайта его, основной сервер не трогаю")
        markServiceClosed(routeLink, url, targets)
        stat(UsageStats.VETOES)
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("меняю сервер для «{}»…", tgt.label), link = mainLink))
        val left = TargetStore(storage.targetsFile).load().firstOrNull { it.id == tgt.id }?.realServers.orEmpty()
        if (left.isEmpty()) {
            storage.log("VETO: у «${tgt.label}» больше нет серверов — запускаю подбор")
            TargetsWorker.enqueue(this)
        } else {
            applyPlanIfChanged()
        }
        PlanBus.notifyChanged()
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("для «{}» выбран другой сервер, откройте его заново", tgt.label), link = mainLink))
        return
    }
    val link = mainLink
    // что на самом деле показывает сайт через этот сервер — в журнал: по нему можно подобрать признаки страницы-заглушки
    withContext(Dispatchers.IO) {
        val port = LocalProxy.port
        val auth = LocalProxy.auth
        if (port > 0 && auth != null) {
            try {
                LocalSocks.get(port, auth, url, connectTimeoutMs = 4_000, readTimeoutMs = 4_000, follow = true, userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").use { r ->
                    val head = r.head(65_536)
                    storage.log("PAGE: $url → код ${r.code}, переходов ${r.hops.size - 1}: ${r.hops.joinToString(" → ")}, адрес после переходов ${r.finalUrl}, узнан как заглушка региона: ${RegionBlock.blocked(r.finalUrl, head)}, антибот Google в цепочке: ${RegionBlock.abuseChain(r.hops)}; текст: ${PageSnippet.of(head)}")
                }
            } catch (e: Throwable) {
                storage.log("PAGE: $url не открылся для разбора: ${e.message}")
            }
        }
    }
    markServiceClosed(link, url, targets)
    storage.log("VETO: вы отметили, что ${ctx.logLabel} не открывается на [$mainTitle] — сервер на 3 дня исключён для этого сайта")
    stat(UsageStats.VETOES)
    smartVerdictText = "вы отметили, что ${ctx.label} здесь не открывается"
    failover(forced = true, smart = true, who = "кнопка «Не работает» (сервис не открывается)")
}

/** [verifyUrl] — сайт/сервис с проверкой региона, который только что был закрыт на прежнем сервере (DIAG): кандидат проверяется
 * и на него, прежде чем смена засчитывается, иначе «подключился» и «сервис открылся» — разные вещи, а следующий DIAG
 * узнает о повторном провале только через 2 минуты новым прогоном. */
internal suspend fun PhoenixVpnService.failover(
    forced: Boolean = false, smart: Boolean = false, verifyUrl: String? = null, serverFailed: Boolean = false,
    who: String = "", requestedAt: Long = System.currentTimeMillis(), // requestedAt вычисляется при вызове, до ожидания замка
) = opLock.withLock {
    if (!wantRunning) return@withLock
    val logTag = if (forced) "SWITCH" else "FAILOVER"
    storage.log("$logTag: " + SwitchTiming.startText(who, System.currentTimeMillis() - requestedAt))
    if (forced && switchModeInsteadOfServer("SWITCH")) return@withLock
    if (!forced && FailoverGate.modeOutOfSync(storage.mode, connectedMode)) {
        // режим сменился, пока ждали очередь: серверы этого режима подберёт смена режима (иначе два перебора подряд перезапускают ядро по кругу)
        storage.log("FAILOVER: режим уже сменился на «${storage.mode.title}» — перебор отменён, сервер подберёт смена режима")
        failures = 0
        return@withLock
    }
    val switchReason = if (smart) "умная смена: $smartVerdictText" else SwitchReason.describe(healthDetail, forced)
    if (!forced) stat(UsageStats.FAILURES)
    if (forced) manualPinLink = null
    if (!forced) openOutage()
    val origLink = mainLink
    // сервер, с которого уходим (по сбою или по просьбе), в ближайшие минуты не выбираем снова (в логе: Italy → UK → Italy за 30 секунд)
    // по сбою — провал (страйк; повтор за тот же эпизод не считается); по просьбе при рабочем сервере — без страйка, просто не первым
    if (!forced || serverFailed) Quarantine.add(ConfigIdentity.key(origLink), networkAlive = networkUsable())
    else Quarantine.skip(ConfigIdentity.key(origLink))
    val origTitle = mainTitle
    val currentId = ConfigIdentity.key(origLink)
    val currentHost = ConfigIdentity.host(origLink)
    val started = System.currentTimeMillis()
    val totalBudget = FailoverBudget.totalMs(forced, NetInfo.kind(this) == NetInfo.Kind.MOBILE)
    fun stageMs(want: Long) = FailoverBudget.stageMs(System.currentTimeMillis(), started, totalBudget, want)
    val rows = ConfigRepository.load(storage)
    // все конфиги режима (мёртвые отметки только понижают очередь), а в чёрных списках — ещё и конфиги белых
    val ordered0 = SearchPool.build(this, storage)
        .filter { XrayConfig.supports(it.link) && it.identity != currentId && (it.host.isEmpty() || it.host != currentHost) }
    if (ordered0.isEmpty()) {
        if (forced) {
            setConnected("других серверов нет")
            storage.log("SWITCH: других конфигов нет — остаюсь на [$origTitle]")
        } else {
            nextFailoverAt = System.currentTimeMillis() + failoverBackoffMs
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи, запасных конфигов нет"), link = mainLink, sub = VpnSub.BROKEN))
            storage.log("VPN: запасных конфигов нет")
        }
        return@withLock
    }
    val foreignCount = ordered0.count { it.mismatch }
    val aware = appAwareOrder(ordered0, force = smart)
    // без открытого приложения: лучшие конфиги не должны быть все одного типа (если оператор режет этот тип, поиск впустую уходит на похожие)
    val ordered = if (appFit == null) ProtocolQuota.diversify(aware) else aware
    storage.log(
        (if (forced) "SWITCH: принудительная смена, текущий [$origTitle], ищу другой среди ${ordered.size}" else "FAILOVER: [$origTitle] не работает, ищу среди ${ordered.size}") +
            " (не для этого режима $foreignCount, неподходящих для общего VPN ${ordered.count { it.unfit }}), сеть ${NetInfo.label(this)}, режим ${storage.mode.title}"
    )
    // теневой режим обучения: единая оценка считается рядом с рейтингом и ничего не меняет — только расхождения в журнал
    try {
        val arms = ArmStore.of(java.io.File(storage.base, "bandit.tsv"))
        val netLabel = NetInfo.label(this)
        val v = Shadow.compare(ordered) { arms.arm(netLabel, storage.mode.name, it.link) }
        if (!v.agrees && v.shadowTop != null && v.actualTop != null) {
            storage.log("SHADOW: по рейтингу первый [${v.actualTop.identity.take(40)} · ${ProtocolLearning.kindOf(v.actualTop.link)}], по оценке обучения [${v.shadowTop.identity.take(40)} · ${ProtocolLearning.kindOf(v.shadowTop.link)}]; рейтинговый первый по оценке на месте ${v.actualRankInShadow} (выбор не менялся)")
        }
    } catch (_: Throwable) {
    }
    // тень рейтинга-суммы (шаг 6 разбора): считается рядом с действующим порядком, ничего не выбирает; пишем только расхождения
    try {
        val sv = RankScore.compare(ordered)
        if (!sv.agrees && sv.scoreTop != null && sv.actualTop != null) {
            storage.log("SCORE: по порядку первый [${sv.actualTop.identity.take(40)}: ${RankScore.describe(sv.actualTop).take(120)}], по сумме доводов [${sv.scoreTop.identity.take(40)}: ${RankScore.describe(sv.scoreTop).take(120)}]; первый по порядку у суммы на месте ${sv.actualRankInScore} (оценка ничего не меняет)")
        }
    } catch (_: Throwable) {
    }
    val prepMs = System.currentTimeMillis() - started
    val searchStartedAt = System.currentTimeMillis()
    SwitchTiming.prepText(prepMs, ordered.size)?.let { storage.log("$logTag: $it") }
    VpnState.set(VpnStatus(VpnPhase.CONNECTING, mainTitle, note = if (forced) tr("меняю сервер…") else tr("ищу рабочий сервер среди {}…", ordered.size), link = mainLink, sub = VpnSub.SEARCHING))
    val sink = SearchSink(storage, this)
    val gnet = NetInfo.label(this)
    val spareLink = SpareServer.take(gnet, System.currentTimeMillis(), currentId, storage.mode.name)
    val fit = appFit
    val spareCand = spareLink?.let { l -> ordered.firstOrNull { it.link == l } }
    // запасной, найденный заранее, подбирался без учёта приложения: берём его, только если он подходит открытому сейчас приложению
    if (spareCand != null && fit != null && spareCand.identity !in fit) storage.log("FAILOVER: запасной найденный заранее не подходит открытому приложению — ищу под него")
    // «Auto» — выбор самой подписки, страна выхода у него плавает: для сервисов с проверкой региона (Gemini, NotebookLM) запасным не берём
    // (в журнале: после «Notebook закрыт» запасным шёл Auto и сразу же оказывался таким же закрытым)
    val spareIsAutoForGeo = spareCand != null && verifyUrl != null && GeoGate.applies(verifyUrl) &&
        ServerKind.isFloatingExit(LinkParser.parse(spareCand.link)?.title?.let { t -> urlDecode(t) }.orEmpty())
    if (spareIsAutoForGeo) storage.log("FAILOVER: запасной найденный заранее — «Auto», для сервиса с проверкой региона не годится — ищу заново")
    val spareFound = spareCand?.takeIf { !spareIsAutoForGeo && (fit == null || it.identity in fit) }?.let { listOf(Found(it, 0L)) }.orEmpty()
    // Кандидат отвечает (tunnelWorks), но сайт/сервис с проверкой региона на нём всё равно закрыт — пробуем следующего,
    // не засчитывая смену: иначе каждая автосмена «попадает» в сервер, который тут же оказывается таким же нерабочим.
    suspend fun stillBlocked(url: String): Boolean = try {
        val port = LocalProxy.port
        val auth = LocalProxy.auth
        if (port <= 0 || auth == null) false else LocalSocks.get(port, auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000, follow = true, maxRedirects = 8, userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").use { r ->
            RegionBlock.closed(r, r.head(65_536))
        }
    } catch (_: Throwable) {
        false // проверить не удалось — не блокируем смену на этом одном основании
    }
    suspend fun tryFound(list: List<Found>): Boolean {
    for (f in list) {
        if (!wantRunning) return true
        var ok = try {
            withContext(Dispatchers.IO) { reloadCore(f.cand.link) }
            tunnelWorks() // дважды подряд: один удачный ответ бывает случайным (в логе серверы гасли через 3–6 секунд после выбора)
        } catch (e: Throwable) {
            storage.log("VPN: запасной не запустился: ${e.message}")
            false
        }
        // туннель поднялся, но нужный сервис здесь всё равно закрыт — это не «сервер плохой», а именно отдельная причина,
        // так что не трогаем общую историю/карантин сервера (он подходит другим приложениям) и просто идём к следующему
        var blockedForService = false
        val vUrl = verifyUrl
        if (ok && vUrl != null && withContext(Dispatchers.IO) { stillBlocked(vUrl) }) {
            blockedForService = true
            storage.log((if (forced) "SWITCH" else "FAILOVER") + ": [${rows.firstOrNull { it.raw == f.cand.link }?.displayName ?: f.cand.identity}] отвечает, но сервис, из-за которого искали, здесь тоже закрыт — пробую следующего")
            markServiceClosed(f.cand.link, vUrl, TargetStore(storage.targetsFile).load())
            ok = false
        }
        if (!blockedForService) {
            if (!forced) recordHist(f.cand.link, ok)
            if (ok) Quarantine.forgive(f.cand.identity)
            if (!ok) Quarantine.add(f.cand.identity, networkAlive = networkUsable()) // ответил поиску, но через туннель связи нет: не возвращаемся к нему сразу
            if (!ok) storage.log((if (forced) "SWITCH" else "FAILOVER") + ": [${rows.firstOrNull { it.raw == f.cand.link }?.displayName ?: f.cand.identity}] ответил поиску (${msText(f.ms)}), но через туннель связи нет" +
                (if (healthDetail.isNotEmpty()) " (причина: $healthDetail)" else ""))
        }
        if (ok) {
            statTick()
            stat(UsageStats.SWITCHES)
            if (smart) stat(UsageStats.SWITCH_SMART)
            if (appFit != null) stat(UsageStats.SWITCH_APP)
            mainTitle = rows.firstOrNull { it.raw == f.cand.link }?.displayName ?: mainTitle
            storage.saveLastConnection(f.cand.link, mainTitle)
            rememberGood(f.cand.link)
            closeOutage(DowntimeStats.FAILOVER)
            failures = 0
            outage.end()
            failoverBackoffMs = PhoenixVpnService.FAILOVER_BACKOFF_START_MS
            if (!forced) {
                val nowSw = System.currentTimeMillis()
                autoSwitchTimes.removeAll { nowSw - it > SwitchStorm.WINDOW_MS }
                autoSwitchTimes.add(nowSw)
                val hold = SwitchStorm.holdMs(autoSwitchTimes, nowSw)
                if (hold > 0L) {
                    nextFailoverAt = nowSw + hold
                    storage.log("FAILOVER: за 10 минут уже ${autoSwitchTimes.size} автосмен — следующую не раньше чем через ${hold / 1000} с, чтобы не рвать связь пачками")
                }
            }
            homeLink = null // прежний основной сервер не работает — возвращаться на него не нужно
            switchedPkg = ""
            setConnected(SwitchReason.note(if (forced) "сервер сменён" else "нашёл рабочий сервер за ${(System.currentTimeMillis() - started) / 1000} с", switchReason))
            if (forced) storage.log("SWITCH: сервер сменён [$origTitle] → [$mainTitle] за ${(System.currentTimeMillis() - started) / 1000} с (${SwitchTiming.text(prepMs, System.currentTimeMillis() - searchStartedAt)})")
            storage.log("VPN: выход нового сервера: " + (ExitStore(storage.exitFile).load()[CheckStore.keyOf(f.cand.link)]?.cc?.let { "$it (проверено)" } ?: "не проверен (страна по названию: ${f.cand.cc.ifEmpty { "?" }})"))
            storage.log("VPN: переключился на запасной [$mainTitle] (${msText(f.ms)}, ${SwitchTiming.text(prepMs, System.currentTimeMillis() - searchStartedAt)}, проверено ${sink.count}; ${sink.statsText()})")
            return true
        }
    }
    return false
    }
    if (spareFound.isNotEmpty()) {
        // сначала быстрая проба временным ядром: мёртвый запасной не должен перезапускать основное ядро (это ещё один обрыв связи)
        val spareAlive = withContext(Dispatchers.IO) { quickAlive(spareFound[0].cand.link) }
        if (spareAlive) {
            storage.log("FAILOVER: пробую запасной сервер, найденный заранее (быстрая проверка прошла)")
            if (tryFound(spareFound)) return@withLock
        } else storage.log("FAILOVER: запасной найденный заранее не ответил на быструю проверку — основное ядро не трогаю, ищу дальше")
    }
    // поиск — гонка: побеждает самый быстрый ответивший. Серверы, где открытый сейчас сервис уже открывался, проверяем первым отдельным этапом
    val readyForApp = ReadyFirst.pick(ordered, fit)
    val offlineAtBefore = ModeDetector.offlineAt
    var found: List<Found> = emptyList()
    if (readyForApp.isNotEmpty()) {
        storage.log((if (forced) "SWITCH" else "FAILOVER") + ": под открытое приложение есть ${readyForApp.size} серверов, где сервис уже открывался — проверяю сначала их")
        found = withContext(Dispatchers.IO) { searchFor(readyForApp, want = 2, budgetMs = stageMs(PhoenixVpnService.FAILOVER_BUDGET_MS / 2).coerceAtLeast(FailoverBudget.MIN_STAGE_MS), sink = sink) }
        if (found.isEmpty()) storage.log((if (forced) "SWITCH" else "FAILOVER") + ": из них никто не отвечает — ищу среди остальных")
    }
    // первый этап закончился на «похоже, нет интернета»: второй этап по тем же серверам даст то же самое, ждать ещё до 2 минут незачем
    val offlineAfterStage = readyForApp.isNotEmpty() && ModeDetector.offlineAt != offlineAtBefore && FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())
    if (offlineAfterStage) storage.log((if (forced) "SWITCH" else "FAILOVER") + ": похоже, интернета нет — остальных серверов сейчас не перебираю")
    if (found.isEmpty() && !offlineAfterStage) {
        val ms = stageMs(PhoenixVpnService.FAILOVER_BUDGET_MS)
        if (ms > 0) found = withContext(Dispatchers.IO) { searchFor(ordered, want = 2, budgetMs = ms, sink = sink) }
        else storage.log((if (forced) "SWITCH" else "FAILOVER") + ": общее время смены (${totalBudget / 1000} с) вышло — остальных серверов не перебираю")
    }
    sink.flush()
    energy("search", sink.count)
    // никто из остальных не отвечает: серверы из карантина получают второй шанс первыми
    val parked = ordered.filter { it.quarantined }
    if (found.isEmpty() && parked.isNotEmpty() && wantRunning && stageMs(PhoenixVpnService.FAILOVER_BUDGET_MS / 3) > 0) {
        Quarantine.releaseAll()
        storage.log("FAILOVER: остальные не отвечают — снимаю карантин и проверяю первыми ${parked.size} серверов из него")
        found = withContext(Dispatchers.IO) { searchFor(parked, want = 2, budgetMs = stageMs(PhoenixVpnService.FAILOVER_BUDGET_MS / 3), sink = sink) }
        sink.flush()
    }
    found.drop(1).firstOrNull { fit == null || it.cand.identity in fit }?.let { SpareServer.remember(it.cand.link, it.cand.identity, gnet, System.currentTimeMillis(), storage.mode.name) }
    if (tryFound(found)) return@withLock
    // Ничего не нашлось: вероятно, нет интернета вообще. Возвращаем прежний сервер и повторим позже (с растущей паузой).
    // mainLink сам по себе не докажет, что возврат не нужен: если кандидат бросил исключение, reloadCore успел
    // остановить ядро и не дошёл до строки, меняющей mainLink, — ядра тогда нет, хотя mainLink не изменился.
    if (wantRunning && (mainLink != origLink || controller?.isRunning != true)) {
        try {
            withContext(Dispatchers.IO) { reloadCore(origLink) }
            mainTitle = origTitle
        } catch (e: Throwable) {
            storage.log("VPN: не удалось вернуть прежний сервер: ${e.message}")
        }
    }
    failures = 0
    stat(UsageStats.NO_SERVER)
    if (forced) {
        setConnected("другой сервер не найден — остаюсь на этом")
        storage.log("SWITCH: другой рабочий сервер не найден (проверено ${sink.count} из ${ordered.size} за ${(System.currentTimeMillis() - started) / 1000} с; ${sink.statsText()}) — остаюсь на [$mainTitle]")
        return@withLock
    }
    nextFailoverAt = System.currentTimeMillis() + failoverBackoffMs
    failoverBackoffMs = (failoverBackoffMs * 2).coerceAtMost(PhoenixVpnService.FAILOVER_BACKOFF_MAX_MS)
    VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи — повторю позже"), link = mainLink, sub = VpnSub.BROKEN))
    storage.log("VPN: рабочий сервер не найден (проверено ${sink.count} из ${ordered.size} за ${(System.currentTimeMillis() - started) / 1000} с; ${sink.statsText()})")
    if (FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())) storage.log("VPN: похоже, интернета нет совсем — полную перепроверку не запускаю, повторю позже")
    else if (Recheck.enqueueIfDue(this, storage.mode, urgent = true)) storage.log("VPN: запустил полную перепроверку всех конфигов")
}

/**
 * Ручной выбор сервера из списка: переключаемся на него, проверяем связь (дважды подряд) и при неудаче возвращаем прежний.
 */
internal suspend fun PhoenixVpnService.switchTo(link: String, title: String) = opLock.withLock {
    if (!wantRunning) return@withLock
    val origLink = mainLink
    val origTitle = mainTitle
    if (ConfigIdentity.key(link) == ConfigIdentity.key(origLink)) {
        storage.log("SWITCH: [$title] уже основной — менять нечего")
        return@withLock
    }
    val started = System.currentTimeMillis()
    VpnState.set(VpnStatus(VpnPhase.CONNECTING, mainTitle, note = tr("перехожу на [{}]…", title), link = mainLink))
    val ok = try {
        withContext(Dispatchers.IO) { reloadCore(link) }
        tunnelWorks()
    } catch (e: Throwable) {
        storage.log("SWITCH: [$title] не запустился: ${e.message}")
        false
    }
    recordHist(link, ok)
    if (ok) {
        mainTitle = title
        storage.saveLastConnection(link, title)
        failures = 0
        outage.end()
        failoverBackoffMs = PhoenixVpnService.FAILOVER_BACKOFF_START_MS
        homeLink = null
        switchedPkg = ""
        manualPinLink = link
        setConnected("сервер выбран вручную")
        storage.log("SWITCH: сервер сменён вручную [$origTitle] → [$title] за ${(System.currentTimeMillis() - started) / 1000} с")
        return@withLock
    }
    storage.log("SWITCH: [$title] не даёт связи (причина: ${healthDetail.ifEmpty { "нет ответа" }}) — возвращаю [$origTitle]")
    try {
        withContext(Dispatchers.IO) { reloadCore(origLink) }
        mainTitle = origTitle
    } catch (e: Throwable) {
        storage.log("SWITCH: не удалось вернуть прежний сервер: ${e.message}")
    }
    setConnected("выбранный сервер не отвечает — остаюсь на этом")
}

/** Поиск рабочих серверов отдельными экземплярами ядра; результаты копятся в [sink] и сохраняются для следующих разов. */
internal suspend fun PhoenixVpnService.searchFor(ordered: List<Candidate>, want: Int, budgetMs: Long, sink: SearchSink): List<Found> {
    val gov = GovernedLimits.get(storage)
    val net = NetInfo.label(this)
    if (gov.level(net) > 0) storage.log("SEARCH: сеть $net не любит много параллельных проверок — иду осторожнее (уровень ${gov.level(net)}, TCP ${gov.tcp(net)})")
    gov.begin(net)
    MemInfo.currentPssMb().let { pss -> if (MemGuard.scale(16, pss) < 16) storage.log("MEM: процесс занимает $pss МБ — параллельных проверок меньше (${MemGuard.scale(16, pss)} вместо 16), чтобы систему не пришлось убивать его по памяти") }
    val search = ServerSearch(
        reach = { h, p -> ServerHosts.reach(h, p, PhoenixVpnService.SEARCH_TCP_TIMEOUT_MS) },
        probe = { cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(this, cfg, url, urgent = true) } },
        tcpParallel = ParallelGovernor.capFor(gov.tcp(net), NetInfo.kind(this) == NetInfo.Kind.MOBILE),
        probeParallel = gov.probe(net, CheckLimits.searchParallel(this)),
        maxCandidates = 3_000,
        loadSample = GatewayProbe.sampler(this),
        // «Отключить» или отзыв VPN не должны ждать конца поиска (до 2 минут) — он обрывается на первой же проверке.
        shouldStop = { !wantRunning },
        offlineSuspect = ServerSearch.offlineLimit(NetInfo.kind(this) == NetInfo.Kind.MOBILE),
    )
    return search.find(ordered, want, budgetMs) { c, ms -> sink.add(c, ms) }.also {
        if (search.throttled > 0) storage.log("SEARCH: роутер отвечал медленно — притормаживал проверки ${search.throttled} раз(а), чтобы не перегружать сеть")
        if (!search.suspectedOffline && gov.finish(net, System.currentTimeMillis())) storage.log("SEARCH: сеть $net выдержала несколько проверок — снимаю ограничение на один уровень")
        gov.save()
        if (search.suspectedOffline) {
            // поиск при обрыве начался из-за сбоя связи: сбой был раньше него, и виноватым в обрыве он быть не может (передаём время первой неудачи)
            if (gov.outage(net, System.currentTimeMillis(), outage.since.takeIf { it > 0L } ?: -1L)) storage.log("SEARCH: сеть $net потеряла интернет во время проверок — в следующий раз параллельных проверок будет меньше")
            gov.save()
            ModeDetector.offlineAt = System.currentTimeMillis()
            storage.log("SEARCH: ${ServerSearch.OFFLINE_SUSPECT} серверов подряд не открыли TCP — похоже, у телефона нет интернета; поиск остановлен, повторю при появлении сети")
        }
    }
}

/**
 * Сменился режим (белые/чёрные списки): сначала как можно быстрее получаем РАБОЧЕЕ соединение — пробуем несколько серверов
 * нового режима (приоритетные ссылки, прошлый сервер, лучшие по рейтингу) и проверяем связь через туннель; если ни один
 * не работает — сразу общий поиск по всем конфигам. Тяжёлые проверки (цели, перепроверка) начинаются только после этого.
 */
internal suspend fun PhoenixVpnService.applyModeIfChanged() {
    val needSearch = opLock.withLock { switchToModeServer() }
    if (needSearch) {
        if (wantRunning) failover(who = "смена режима")
    }
}

/** @return true — быстрые попытки не удались, нужен общий поиск (вызывается уже без блокировки). */
internal suspend fun PhoenixVpnService.switchToModeServer(): Boolean {
    if (!wantRunning || tun == null) return false
    val mode = storage.mode
    if (mode == connectedMode) return false
    val from = connectedMode
    connectedMode = mode
    manualPinLink = null
    modeSwitchedAt = System.currentTimeMillis()
    slow.reset()
    val started = System.currentTimeMillis()
    openOutage()
    val allPicks = withContext(Dispatchers.IO) { QuickConnect.picksFor(this@switchToModeServer, PhoenixVpnService.MODE_QUICK_TRIES) }
    var picks = allPicks
    if (picks.isEmpty()) {
        storage.log("MODE: режим «${mode.title}» (был «${from.title}»), серверов для него нет — оставил текущий")
        return false
    }
    storage.log("MODE: режим «${from.title}» → «${mode.title}», пробую быстро найти рабочий из ${picks.size}: " + picks.joinToString { "[${it.title}]" })
    // сначала параллельная предпроверка (без перезапуска туннеля), потом по одному — только те, кто ответил
    val mainKey = ConfigIdentity.key(mainLink)
    val pre = prefilterPicks(picks.filter { ConfigIdentity.key(it.link) != mainKey })
    if (pre != null) {
        val okKeys = pre.map { ConfigIdentity.key(it.link) }.toSet()
        picks = picks.filter { val k = ConfigIdentity.key(it.link); k == mainKey || k in okKeys }
        storage.log("MODE: предпроверка параллельно за ${(System.currentTimeMillis() - started) / 1000} с — ответили ${pre.size} из ${allPicks.count { ConfigIdentity.key(it.link) != mainKey }}" + (if (picks.isEmpty()) ", переходить некуда — иду к полному поиску" else ""))
    }
    for ((i, pick) in picks.withIndex()) {
        if (!wantRunning) return false
        val tryStart = System.currentTimeMillis()
        foreignNote = if (pick.foreign != null) "в режиме «${mode.title}» своих серверов нет — сервер из режима «${pick.foreign.title}»" else ""
        val same = ConfigIdentity.key(pick.link) == ConfigIdentity.key(mainLink)
        val ok = try {
            if (!same) withContext(Dispatchers.IO) { reloadCore(pick.link) }
            tunnelWorks()
        } catch (e: Throwable) {
            storage.log("MODE: [${pick.title}] не запустился: ${e.message}")
            false
        }
        recordHist(pick.link, ok)
        if (!ok) {
            storage.log("MODE: [${pick.title}] не даёт связи (попытка ${i + 1} из ${picks.size}, ${(System.currentTimeMillis() - tryStart) / 1000} с)")
            continue
        }
        mainTitle = pick.title
        homeLink = null
        switchedPkg = ""
        failures = 0
        if (pick.foreign == null) storage.saveLastConnection(pick.link, pick.title)
        setConnected(foreignNote.ifBlank { if (same) "режим «${mode.title}»: прежний сервер подходит" else "режим «${mode.title}»: взял сервер этого режима" })
        modeSwitchedAt = System.currentTimeMillis()
        storage.log("VPN: режим «${mode.title}», работает [${pick.title}] (попытка ${i + 1}, ${(System.currentTimeMillis() - started) / 1000} с)")
        rememberGood(pick.link)
        closeOutage(DowntimeStats.MODE)
        // связь есть — теперь можно тратить силы на остальное: цели и перепроверка нового режима (цели чуть позже: сейчас возможна пересборка обхода)
        TargetsWorker.enqueue(this, PhoenixVpnService.TARGETS_AFTER_REBUILD_SEC)
        Recheck.enqueueIfDue(this, mode)
        return false
    }
    storage.log("MODE: из ${picks.size} быстрых вариантов ни один не работает — ищу среди всех конфигов режима «${mode.title}»")
    return true
}
