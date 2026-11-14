package com.phoenix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnPhase { IDLE, CONNECTING, CONNECTED, ERROR }

/** Это сообщение «нет связи…»? Тексты переводятся, поэтому сравниваем и с переводом, и с русским началом. */
fun isNoLinkNote(note: String): Boolean =
    note.startsWith("нет связи") || listOf(
        "нет связи через этот сервер",
        "нет связи: у телефона нет сети",
        "нет связи: у сети нет интернета",
        "нет связи, запасных конфигов нет",
        "нет связи — повторю позже",
    ).any { note == tr(it) }

data class VpnStatus(
    val phase: VpnPhase = VpnPhase.IDLE,
    val title: String = "",
    val error: String = "",
    /** Пояснение к состоянию: «ищу запасной сервер», «переключился на сервер Telegram» и т. п. */
    val note: String = "",
    /** Ссылка конфига, через который работает VPN (нужна экрану, чтобы считать запасные). */
    val link: String = "",
)

/** Состояние VPN для интерфейса. Меняется из сервиса. */
object VpnState {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    /** Текущий сервер закреплён вручную (автоперебор его не трогает) — для кнопки «Открепить». */
    val pinned = MutableStateFlow(false)

    fun set(status: VpnStatus) {
        _status.value = status
    }
}

/** Выбранный для подключения конфиг; [foreign] — из какого другого режима он взят (у текущего своих конфигов нет). */
data class Pick(val link: String, val title: String, val foreign: Mode? = null)

/** Выбор конфига «по умолчанию» для кнопки в шторке и быстрых подключений: всегда из данных текущего режима. */
object QuickConnect {
    fun pickEx(context: Context): Pick? {
        val storage = Storage(context)
        pickIn(context, storage)?.let { return it }
        // у режима нет своих конфигов (например, у чёрных списков не заданы источники): берём из другого и говорим об этом
        for (m in Mode.entries) {
            if (m == storage.mode) continue
            pickIn(context, Storage(context, m))?.let { return it.copy(foreign = m) }
        }
        return null
    }

    /**
     * Несколько серверов для быстрого подключения по порядку: приоритетные ссылки, прошлый сервер режима, лучшие по рейтингу.
     * Если у режима своих конфигов нет — из другого режима (с пометкой).
     */
    fun picksFor(context: Context, limit: Int = 4): List<Pick> {
        val storage = Storage(context)
        val own = picksIn(context, storage, limit)
        if (own.isNotEmpty()) return own
        for (m in Mode.entries) {
            if (m == storage.mode) continue
            val other = picksIn(context, Storage(context, m), limit)
            if (other.isNotEmpty()) return other.map { it.copy(foreign = m) }
        }
        return emptyList()
    }

    private fun picksIn(context: Context, storage: Storage, limit: Int): List<Pick> {
        val rows = ConfigRepository.load(storage)
        if (rows.isEmpty()) return emptyList()
        val checks = CheckStore(storage.checksFile).load()
        val ranked = Ranking.rank(
            Ranking.candidates(rows, checks, history = storage.historyView(context), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())
        ).filter { XrayConfig.supports(it.link) }
        val last = pickIn(context, storage)?.link
        val names = rows.associate { it.raw to it.displayName }
        val good = try { LastGoodStore.of(java.io.File(storage.base, "lastgood.tsv")).get(NetInfo.label(context), storage.mode.name, System.currentTimeMillis()) } catch (_: Throwable) { null }
        return QuickOrder.order(ranked, last, limit, good).map { Pick(it, names[it] ?: "Phoenix") }
    }

    /** @return пара (ссылка, название) либо null, если подключаться не к чему */
    fun pick(context: Context): Pair<String, String>? = pickEx(context)?.let { it.link to it.title }

    private fun pickIn(context: Context, storage: Storage): Pick? {
        val rows = ConfigRepository.load(storage)
        // последний конфиг этого режима; если сервер в списке с тех пор сменил адрес — берём свежую ссылку с тем же именем
        var last = storage.lastConnection()
        if (last == null && storage.mode == Mode.WHITELIST) {
            val old = AppSettings(context)
            if (old.lastLink.isNotBlank()) last = old.lastLink to old.lastTitle // перенос из версий до 3.6
        }
        if (last != null && XrayConfig.supports(last.first)) {
            if (rows.isEmpty()) return Pick(last.first, last.second.ifBlank { "Phoenix" })
            val id = ConfigIdentity.key(last.first)
            val fresh = rows.firstOrNull { it.raw == last.first } ?: rows.firstOrNull { ConfigIdentity.key(it.raw) == id }
            if (fresh != null && XrayConfig.supports(fresh.raw)) return Pick(fresh.raw, fresh.displayName)
        }
        if (rows.isEmpty()) return null
        val checks = CheckStore(storage.checksFile).load()
        val best = Ranking.rank(Ranking.candidates(rows, checks, history = storage.historyView(context), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds()))
            .firstOrNull { XrayConfig.supports(it.link) } ?: return null
        return Pick(best.link, rows.firstOrNull { it.raw == best.link }?.displayName ?: "Phoenix")
    }
}

class PhoenixVpnService : VpnService() {
    // необработанная ошибка в фоновой задаче службы не должна ронять всё приложение: пишем в журнал и продолжаем
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            try { Storage(this).log("ERROR: необработанная ошибка в службе: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
        }
    )
    private val opLock = Mutex()

    private var tun: ParcelFileDescriptor? = null
    private var controller: XrayCore.Controller? = null
    private var mainLink = ""
    private var mainTitle = ""
    private var healthPort = 0
    @Volatile private var healthAuth: SocksAuth? = null
    /** Почему последняя проверка здоровья не прошла (по каждому адресу): идёт в строку HEALTH. */
    @Volatile private var healthDetail = ""
    /** Идёт принудительная смена сервера (повторные нажатия игнорируются). */
    @Volatile private var switching = false

    /** IPv6 «в туннель» включён, но сервер IPv6 не пропускает: временно закрываем IPv6 правилом, чтобы приложения сразу шли по IPv4. */
    @Volatile private var ipv6Auto: Ipv6Mode? = null
    private var activeRoutes: List<AppRoute> = emptyList()
    private var monitors: List<Job> = emptyList()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var idleReceiver: BroadcastReceiver? = null
    @Volatile private var wantRunning = false
    @Volatile private var failures = 0
    @Volatile private var lastPrewarmSlot = -1
    @Volatile private var startPending = false
    private var pendingLink = ""
    private var lastStartAt = 0L
    /** Режим, данные которого сейчас в работе: при его смене подключение переносится на сервер нового режима. */
    private var connectedMode: Mode = Mode.WHITELIST
    private var foreignNote = ""
    private val slow = SlowMonitor(0.5)
    private val shape = ShapingDetector()
    private val appHours by lazy { AppHours(java.io.File(filesDir, "app_hours")) }
    @Volatile private var lastAppPrepSlot = -1
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastOkRecordAt = 0L
    private var nextFailoverAt = 0L
    @Volatile private var modeSwitchedAt = 0L
    /** Сервер, выбранный вручную: автоперебор его не трогает, пока ядро работает (иначе telegram-only уходит из-за общей проверки). */
    @Volatile private var pinBacking: String? = null
    private var manualPinLink: String?
        get() = pinBacking
        set(v) { pinBacking = v; VpnState.pinned.value = v != null }
    /** С какого момента у закреплённого вручную сервера не проходит общая проверка (0 — проходит). */
    @Volatile private var pinFailSince = 0L
    private var failoverBackoffMs = FAILOVER_BACKOFF_START_MS
    private var lastDetectAt = 0L
    private val lastSwitchAt = HashMap<String, Long>()
    /** Кэш [hasSwitchTargets]: иначе опрос переднего плана читает targets.tsv дважды за круг, в том числе при выключенном экране. Сбрасывается через [PlanBus]. */
    @Volatile private var switchTargetsCache: Boolean? = null
    private lateinit var settings: AppSettings

    /** Каждый раз свежий: режим (белые/чёрные списки) мог смениться, пока служба работает. */
    private val storage: Storage get() = Storage(this)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { disconnect() }
            ACTION_SWITCH -> {
                if (!wantRunning || tun == null) storage.log("SWITCH: VPN не запущен — менять нечего")
                else if (switching) storage.log("SWITCH: смена уже идёт — повторное нажатие проигнорировано")
                else {
                    switching = true
                    storage.log("SWITCH: запрос на принудительную смену сервера, сеть ${NetInfo.label(this)}")
                    scope.launch {
                        try {
                            failover(forced = true)
                        } finally {
                            switching = false
                        }
                    }
                }
            }
            ACTION_SMART -> {
                if (!wantRunning || tun == null) storage.log("SMART: VPN не запущен — менять нечего")
                else if (switching) storage.log("SMART: смена уже идёт — повторное нажатие проигнорировано")
                else {
                    switching = true
                    scope.launch {
                        try {
                            smartSwitch()
                        } finally {
                            switching = false
                        }
                    }
                }
            }
            ACTION_BYPASS -> {
                // приложения «мимо VPN» задаются при создании туннеля; без пересоздания новый список начинал работать только после ручного переподключения
                if (wantRunning && tun != null && !switching) {
                    switching = true
                    scope.launch {
                        try {
                            applyBypassNow()
                        } finally {
                            switching = false
                        }
                    }
                }
            }
            ACTION_VETO -> {
                if (!wantRunning || tun == null) storage.log("VETO: VPN не запущен")
                else if (switching) storage.log("VETO: смена уже идёт — повторное нажатие проигнорировано")
                else {
                    switching = true
                    scope.launch {
                        try {
                            vetoCurrent()
                        } finally {
                            switching = false
                        }
                    }
                }
            }
            ACTION_UNPIN -> {
                if (manualPinLink == null) storage.log("PIN: закрепления нет — снимать нечего")
                else {
                    manualPinLink = null
                    pinFailSince = 0L
                    nextFailoverAt = 0L
                    storage.log("PIN: закрепление снято пользователем — [$mainTitle] теперь меняется само, как обычно")
                    if (wantRunning && VpnState.status.value.phase == VpnPhase.CONNECTED) setConnected(foreignNote)
                }
            }
            ACTION_SWITCH_TO -> {
                val link = intent.getStringExtra(EXTRA_LINK).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Phoenix" }
                if (!wantRunning || tun == null) storage.log("SWITCH: VPN не запущен — переключаться некуда")
                else if (link.isBlank()) storage.log("SWITCH: сервер для переключения не указан")
                else if (switching) storage.log("SWITCH: смена уже идёт — выбор [$title] проигнорирован")
                else {
                    switching = true
                    storage.log("SWITCH: выбран вручную [$title], сеть ${NetInfo.label(this)}")
                    scope.launch {
                        try {
                            switchTo(link, title)
                        } finally {
                            switching = false
                        }
                    }
                }
            }
            ACTION_START -> {
                var link = intent.getStringExtra(EXTRA_LINK).orEmpty()
                var title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val now = android.os.SystemClock.elapsedRealtime()
                // Несколько команд «подключить» подряд (плитка, открытие приложения, загрузка телефона) не должны
                // превращаться в серию переподключений: повтор того же самого сразу после запуска игнорируем.
                val busyOrUp = startPending || (wantRunning && VpnState.status.value.phase != VpnPhase.ERROR)
                if (busyOrUp && now - lastStartAt < START_DEBOUNCE_MS && (link.isBlank() || link == mainLink || link == pendingLink)) {
                    storage.log("VPN: повторная команда подключения проигнорирована")
                    return START_NOT_STICKY
                }
                lastStartAt = now
                // выставляем сразу, а не только внутри connect() при успехе: раньше между этой командой и её завершением
                // (десятки — сотни мс до начала корутины, иногда секунды на сам connect) settings.vpnWanted оставался
                // старым значением, и итоговый START_STICKY/START_NOT_STICKY в конце функции зависел от гонки — случись
                // гибель процесса именно в этом окне, служба не поднималась бы заново (находка Фейбла №3). Если подключение
                // в итоге не выйдет и kill switch не держит туннель, fail() сам вернёт значение в false.
                settings.vpnWanted = true
                val explicit = link.isNotBlank() // конфиг выбран пользователем (кнопка «Подключить» у конфига)
                // Сначала сразу поднимаем уведомление: Android требует вызвать startForeground за считаные секунды после
                // startForegroundService, иначе приложение «падает». Выбор конфига (рейтинг тысяч конфигов) — уже в фоне.
                showForeground(title.ifBlank { "Phoenix" }, "Подключение…")
                startPending = true
                pendingLink = link
                scope.launch {
                    try {
                        var foreign: Mode? = null
                        if (link.isBlank()) {
                            QuickConnect.pickEx(this@PhoenixVpnService)?.let { p -> link = p.link; title = p.title; foreign = p.foreign }
                        }
                        if (link.isBlank()) {
                            fail("Нет конфига для подключения: выберите его в списке", title)
                        } else {
                            pendingLink = link
                            connect(link, title, foreign)
                            if (explicit && wantRunning && mainLink == link) { manualPinLink = link; setConnected(foreignNote) }
                        }
                    } catch (e: Throwable) {
                        storage.log("VPN: ошибка запуска: ${e.javaClass.simpleName}: ${e.message}")
                        fail("Ошибка запуска: ${e.message ?: e.javaClass.simpleName}", title)
                    }
                }
            }
            else -> if (!wantRunning) {
                // Служба перезапущена системой без команды: это либо гибель процесса, пока VPN должен был работать
                // (прошивка выгрузила, обновление поставили поверх), либо системный запуск «Постоянного VPN»
                // (android.net.VpnService). В обоих случаях поднимаем туннель заново сами, а не сворачиваемся
                // до ручного открытия приложения — с защитой от цикла, если процесс гибнет снова и снова.
                val wanted = settings.vpnWanted || intent?.action == VpnService.SERVICE_INTERFACE
                if (wanted && XrayCore.available && VpnService.prepare(this) == null && settings.noteVpnRestart()) {
                    storage.log("VPN: автоподключение [процесс перезапущен]")
                    return onStartCommand(Intent(this, PhoenixVpnService::class.java).setAction(ACTION_START), flags, startId)
                }
                settings.vpnWanted = false
                stopSelf()
            }
        }
        return if (settings.vpnWanted) START_STICKY else START_NOT_STICKY
    }

    // ---- подключение ----

    /** Набор приложений, не идущих через VPN в текущем туннеле. */
    @Volatile private var appliedBypass: Set<String> = emptySet()

    private suspend fun applyBypassNow() {
        val want = BypassStore(storage.base, storage.mode, NetInfo.label(this)).effective(installedPackagesForBypass())
        if (want == appliedBypass) {
            storage.log("BYPASS: список обхода не изменился (${want.size}) — туннель не трогаю")
            return
        }
        val added = (want - appliedBypass).size
        val removed = (appliedBypass - want).size
        storage.log("BYPASS: список обхода изменился (добавлено $added, убрано $removed) — пересоздаю туннель с новым списком, сервер прежний [$mainTitle]")
        val link = mainLink
        val title = mainTitle
        if (!wantRunning || link.isBlank()) return
        connect(link, title)
    }

    private suspend fun connect(link: String, title: String, foreign: Mode? = null) = opLock.withLock {
        startPending = false
        wantRunning = true
        failures = 0
        hevCrashed = false
        manualPinLink = null
        stopMonitors()
        teardown()
        mainLink = link
        mainTitle = title
        connectedMode = storage.mode
        foreignNote = if (foreign != null) "в режиме «${connectedMode.title}» своих серверов нет — сервер из режима «${foreign.title}»" else ""
        homeLink = null
        switchedPkg = ""
        awaySince = 0L
        lastRx = -1L
        slow.reset()
        ipv6Auto = null
        VpnState.set(VpnStatus(VpnPhase.CONNECTING, title, link = link))
        try {
            withContext(Dispatchers.IO) {
                XrayCore.init(this@PhoenixVpnService)
                tun = buildInterface()
                startCore(link)
            }
            if (foreign == null) storage.saveLastConnection(link, title)
            setConnected(foreignNote)
            statLast = System.currentTimeMillis()
            stat(UsageStats.SESSIONS)
            recordHist(link, true)
            storage.log("VPN: подключено [$title] (режим ${connectedMode.title})")
            settings.vpnWanted = true
            logEnvironment()
            startMonitors()
        } catch (e: Throwable) {
            val message = e.message ?: e.javaClass.simpleName
            storage.log("VPN: ошибка [$title]: $message")
            if (settings.killSwitch && tun != null) {
                // Защита от утечек: туннель остаётся поднятым, трафик никуда не уходит; наблюдатель будет искать рабочий сервер.
                // vpnWanted ставим явно и здесь: если это самое первое подключение (после установки или ручного «Отключить»),
                // оно было false, и раньше здесь не менялось — после гибели процесса или перезагрузки служба не поднялась бы
                // заново, и телефон вышел бы в сеть напрямую, хотя пользователь последним видел «интернет заблокирован» (находка №4)
                settings.vpnWanted = true
                controller?.stop()
                controller = null
                activeRoutes = emptyList()
                VpnState.set(VpnStatus(VpnPhase.CONNECTED, title, note = tr("нет защищённого соединения — интернет заблокирован ({})", message), link = link))
                showForeground(title.ifBlank { "Phoenix" }, "Интернет заблокирован: ищу сервер")
                startMonitors()
            } else {
                teardown()
                wantRunning = false
                fail(message, title)
            }
        }
    }

    /** Одна строка с условиями, в которых работает туннель: без неё по журналу не понять, почему на разных телефонах по-разному. */
    private fun logEnvironment() {
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            val fw = try { Background.firmware().text() } catch (_: Throwable) { "прошивка неизвестна" }
            storage.log(
                "ENV: Phoenix $version · $fw · сеть ${NetInfo.label(this)} · автопереподключение ${if (settings.autoReconnect) "вкл" else "выкл"}, " +
                    "смена при низкой скорости ${if (settings.slowSwitch) "вкл (порог ${"%.1f".format(java.util.Locale.US, settings.slowMinMbps)} Мбит/с)" else "выкл"}, " +
                    "политика целей «${settings.globalPolicy.title}», автоопределение режима ${if (settings.autoMode) "вкл" else "выкл"}"
            )
        } catch (_: Throwable) {
        }
    }

    private fun fail(message: String, title: String) {
        startPending = false
        settings.vpnWanted = false
        VpnState.set(VpnStatus(VpnPhase.ERROR, title, message))
        stopForegroundCompat()
        stopSelf()
    }

    private fun setConnected(note: String = "") {
        // закреплённый вручную сервер помечаем, чтобы было видно, почему автоперебор молчит
        val shown = if (manualPinLink != null && manualPinLink == mainLink) "📌 закреплён" + (if (note.isBlank()) "" else " · $note") else note
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = shown, link = mainLink))
        showForeground(mainTitle.ifBlank { "Phoenix" }, shown.ifBlank { "Подключено" })
    }

    private fun uidOf(pkg: String): String? = try {
        packageManager.getPackageUid(pkg, 0).toString()
    } catch (_: Exception) {
        null
    }

    private fun freePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (_: Exception) {
        0
    }

    /** Узнаёт ли ядро приложение по соединению (проверяется при первом запуске ядра с маршрутами по приложениям). */
    @Volatile private var finderOk = true

    private fun currentRoutes(link: String): List<AppRoute> =
        PlanBuilder.routes(
            link, TargetStore(storage.targetsFile).load(), settings.globalPolicy,
            finderOk = finderOk, usageOk = ForegroundWatcher.hasAccess(this), uidOf = ::uidOf,
        )

    /** Запускает ядро с [link] как основным; интерфейс TUN остаётся тем же (как делает v2rayNG при перезагрузке). */
    private fun startCore(link: String, forceBuiltin: Boolean = false) {
        HevTunnel.stop()
        hevActive = false
        val routes = currentRoutes(link)
        val engine = if (forceBuiltin) TunEngine.Choice(TunEngine.Kind.BUILTIN, "hev не запустился")
        else TunEngine.choose(settings.hevEngine, HevTunnel.available && !hevCrashed, routes.any { it.uids.isNotEmpty() })
        val useHev = engine.kind == TunEngine.Kind.HEV
        val tunPort = if (useHev) freePort() else 0
        val tunAuth = if (useHev) SocksAuth.random() else null
        healthPort = freePort()
        val auth = SocksAuth.random()
        healthAuth = auth
        LocalProxy.auth = auth
        LocalProxy.port = healthPort
        // адреса серверов-доменов находим сами (приложение вне VPN, системный DNS работает)
        val hosts = HashMap<String, String>()
        for (l in listOf(link) + routes.map { it.link }) {
            val h = ServerHosts.hostsForLink(l)
            hosts += h
            // домен сервера не разрешился/не ответил — раньше молча; теперь видно в журнале, почему ядро может не подключиться (находка №10)
            val host = LinkParser.parse(l)?.host
            if (h.isEmpty() && host != null && !host.any { it == ':' } && !host.all { it.isDigit() || it == '.' }) {
                storage.log("DNS: домен сервера не удалось разрешить заранее (${LogMask.host(host)}) — ядро попробует само")
            }
        }
        val opts = XrayOptions(
            routes = routes,
            bypassDomains = BypassStore(storage.base, storage.mode, NetInfo.label(this)).let { b ->
                // сайты выбранных для обхода приложений (ссылки в браузере открываются так же, как приложение) — тоже мимо VPN
                val appSites = if (b.appLinksEnabled) try { AppLinks.rulesFor(this, b.effective(installedPackagesForBypass())) } catch (_: Throwable) { emptyList() } else emptyList()
                b.siteRules(appSites)
            },
            healthPort = healthPort,
            healthAuth = auth,
            hosts = hosts,
            ipv6 = ipv6Auto ?: settings.ipv6Mode,
            blockQuic = settings.blockQuic,
            tunSocksPort = tunPort,
            tunSocksAuth = tunAuth,
        )
        val config = XrayConfig.vpnConfig(link, opts) // понятная ошибка, если ссылка не поддерживается
        // для разбора жалоб «работает только часть приложений»: какие маршруты и настройки действовали при запуске
        storage.log(
            "VPN: движок туннеля — ${engine.kind.title} (${engine.why}). " +
            "VPN: ядро запущено с настройками режима «${storage.mode.title}»: IPv6 ${opts.ipv6.name}, QUIC ${if (opts.blockQuic) "закрыт" else "открыт"}, " +
                "защита от обрыва ${if (settings.killSwitch) "вкл" else "выкл"}; маршрутов по приложениям/сайтам: ${routes.size}" +
                routes.joinToString("") { " [${it.tag}: приложений ${it.uids.size}, сайтов ${it.domains.size}]" }
        )
        val c = XrayCore.Controller()
        if (routes.any { it.uids.isNotEmpty() }) {
            val ok = c.registerProcessFinder(::findUid)
            if (!ok && finderOk) {
                // ядро не умеет определять приложение по соединению: маршруты по приложениям не сработают.
                // Пересоберём план без них (приложения перейдут на вариант Б, если выдан доступ, иначе останутся на общем сервере).
                finderOk = false
                storage.log("VPN: ядро не определяет приложения по соединению, переход на вариант Б")
                c.stop()
                return startCore(link, forceBuiltin)
            }
        }
        c.start(config, if (useHev) 0 else tun!!.fd)
        if (!c.isRunning) throw IllegalStateException("Ядро не запустилось")
        if (useHev) {
            val yaml = TunEngine.hevYaml(
                mtu = MTU, ipv4 = TUN_ADDRESS, ipv6 = if (settings.ipv6Mode != Ipv6Mode.OFF) TUN_ADDRESS_V6 else null,
                port = tunPort, user = tunAuth?.user, pass = tunAuth?.pass,
            )
            if (!HevTunnel.start(this, yaml, tun!!.fd)) {
                storage.log("VPN: hev не запустился, переход на встроенный движок")
                c.stop()
                return startCore(link, forceBuiltin = true)
            }
        }
        hevActive = useHev
        controller = c
        activeRoutes = routes
    }

    /** hev-туннель сейчас работает / упал в этой сессии (до её конца остаётся встроенный движок). */
    @Volatile private var hevActive = false
    @Volatile private var hevCrashed = false

    /** Перезапуск ядра с другим основным конфигом. Вызывать под opLock. */
    private fun reloadCore(link: String) {
        coreGen++
        try {
            controller?.stop()
            controller = null
            startCore(link)
            mainLink = link
            disturbedAt = System.currentTimeMillis()
        } finally {
            coreGen++
        }
    }

    /** Меняется при каждом перезапуске ядра: результат проверки связи, начатой при другом значении, устарел. */
    @Volatile private var coreGen = 0

    private fun findUid(protocol: String, srcIp: String, srcPort: Long, dstIp: String, dstPort: Long): Long {
        val cm = getSystemService(ConnectivityManager::class.java)
        val proto = if (protocol.equals("udp", true)) 17 else 6
        return try {
            cm.getConnectionOwnerUid(proto, InetSocketAddress(srcIp, srcPort.toInt()), InetSocketAddress(dstIp, dstPort.toInt())).toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun installedPackagesForBypass(): List<String> = try {
        packageManager.getInstalledApplications(0).map { it.packageName }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun buildInterface(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("Phoenix")
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        // IPv6: если его не заводить в туннель, трафик по IPv6 пойдёт мимо VPN (утечка).
        if (settings.ipv6Mode != Ipv6Mode.OFF) {
            builder.addAddress(TUN_ADDRESS_V6, 126).addRoute("::", 0)
        }

        // Само приложение всегда мимо VPN: иначе соединения ядра к серверу пошли бы в собственный туннель.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }

        val installed = try {
            packageManager.getInstalledApplications(0).map { it.packageName }
        } catch (_: Throwable) {
            emptyList()
        }
        val store = BypassStore(storage.base, storage.mode, NetInfo.label(this))
        val bypass = store.effective(installed)
        appliedBypass = bypass
        val found = store.discovered(installed)
        if (found.isNotEmpty()) storage.log("BYPASS: найдено российских приложений вне набора: ${found.size} (${found.take(8).joinToString()}${if (found.size > 8) "…" else ""}) — добавлены в обход")
        var skipped = 0
        for (pkg in bypass) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                skipped++ // приложения нет на телефоне — это нормально
            }
        }
        storage.log("VPN: в обход ${bypass.size - skipped} приложений (не установлено: $skipped)")
        // по Steam и подобным: видно, в обходе ли они и какие из них установлены (если пакет не виден системе — его в списке нет)
        val openKeys = OpenServices.PACKAGES.filter { it in installed || it in bypass }
        storage.log("BYPASS: из зарубежных открытых сервисов установлено ${openKeys.size}: " + openKeys.joinToString { p -> p.substringAfterLast('.') + (if (p in bypass) " ✓обход" else " ✗не в обходе") })

        return builder.establish() ?: throw IllegalStateException("Система не выдала VPN-интерфейс (нет разрешения на VPN?)")
    }

    // ---- наблюдение за туннелем ----

    private fun startMonitors() {
        stopMonitors()
        lastNetLabel = NetInfo.label(this)
        val jobs = ArrayList<Job>()

        // 1) Проверка живости туннеля.
        jobs += scope.launch {
            var calm = 0
            while (true) {
                // необработанное исключение внутри тела цикла убивает именно эту корутину насовсем (SupervisorJob
                // только логирует и не перезапускает её) — тогда проверка связи не работает до переподключения,
                // хотя журнал пишет «и продолжаем». Ловим здесь, чтобы один сбой не останавливал цикл навсегда.
                try {
                    val pm = getSystemService(PowerManager::class.java)
                    val screenOn = pm == null || pm.isInteractive
                    delay(HealthPace.nextDelay(screenOn, failures, calm, hot = PowerInfo.hot(this@PhoenixVpnService), lowBattery = PowerInfo.lowBattery(this@PhoenixVpnService)))
                    if (storage.mode != connectedMode) PlanBus.notifyChanged() // режим сменили: перенесём подключение
                    if (screenOn) try { ActivityStore.get(this@PhoenixVpnService).recordActive() } catch (_: Throwable) {}
                    if (screenOn) withContext(Dispatchers.IO) { recordAppUse() }
                    prepareApps()
                    if (!screenOn && failures == 0) {
                        // экран выключен — не будим сеть зря; но перед обычным пиком активности проверим туннель заранее (раз в слот)
                        val act = ActivityStore.get(this@PhoenixVpnService)
                        val slot = act.slotNow()
                        if (settings.quietAuto && slot != lastPrewarmSlot && act.peakSoon()) {
                            lastPrewarmSlot = slot
                            storage.log("ACT: скоро обычная активность — проверяю туннель заранее")
                            checkHealthOnce(force = true)
                        }
                        continue
                    }
                    checkHealthOnce()
                    calm = if (failures == 0 && disturbedAt < System.currentTimeMillis() - 60_000L) calm + 1 else 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    storage.log("ERROR: монитор связи: ${e.javaClass.simpleName}: ${e.message}")
                    delay(30_000L)
                }
            }
        }

        // 1б) Цвет уведомления следует за состоянием: зелёный, жёлтый (ищем сервер), красный (нет связи)
        jobs += scope.launch {
            VpnState.status.collect { st ->
                if (!wantRunning || st.phase == VpnPhase.IDLE || st.phase == VpnPhase.ERROR) return@collect
                val tone = NotifyTone.of(st.phase == VpnPhase.CONNECTED, st.phase == VpnPhase.CONNECTING, st.note)
                if (tone != lastTone && lastNotifTitle.isNotEmpty()) showForeground(lastNotifTitle, st.note.ifBlank { lastNotifText }, tone)
            }
        }

        // 2) Изменился список целей или их серверы — пересобрать маршруты.
        jobs += scope.launch {
            PlanBus.changes.collect {
                switchTargetsCache = null // список целей мог измениться — старое «есть ли цели с вариантом Б» не годится
                delay(2_000)
                applyModeIfChanged()
                applyPlanIfChanged()
            }
        }

        // 3) Вариант Б: приложение вышло на передний план.
        jobs += scope.launch {
            while (true) {
                try {
                    // опрос «какое приложение открыто» нужен только если есть цели с вариантом Б — иначе не тратим батарею;
                    // кэш вместо чтения targets.tsv на каждом круге (сбрасывается через PlanBus при изменении целей)
                    val hasTargets = switchTargetsCache ?: hasSwitchTargets().also { switchTargetsCache = it }
                    delay(if (hasTargets) FOREGROUND_PERIOD_MS else IDLE_PERIOD_MS)
                    watchForeground()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    storage.log("ERROR: опрос переднего плана: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        // 5) Тип блокировок меняется (дом ↔ дорога, оператор включил белые списки): проверяем раз в 5 минут, пока экраном пользуются.
        jobs += scope.launch {
            while (true) {
                try {
                    delay(60_000L)
                    val pm = getSystemService(PowerManager::class.java)
                    if (pm != null && !pm.isInteractive) continue
                    if (System.currentTimeMillis() - lastDetectAt >= DETECT_PERIOD_MS) detectMode()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    storage.log("ERROR: определение режима: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        // 6) Сразу после подключения проверяем связь через 4 секунды, а не через 25: нерабочий сервер видно быстро.
        jobs += scope.launch {
            delay(4_000)
            checkHealthOnce(force = true)
        }

        // 7) IPv6 в туннеле, а сервер его не пропускает: часть приложений зависает (Telegram ходит по IPv4 и работает, остальные нет).
        jobs += scope.launch {
            delay(12_000)
            if (!wantRunning || settings.ipv6Mode != Ipv6Mode.TUNNEL || ipv6Auto != null) return@launch
            val verdict = withContext(Dispatchers.IO) { ipv6Verdict() }
            if (verdict == false) {
                opLock.withLock {
                    if (!wantRunning || ipv6Auto != null) return@withLock
                    ipv6Auto = Ipv6Mode.BLOCK
                    try {
                        withContext(Dispatchers.IO) { reloadCore(mainLink) }
                        storage.log("VPN: сервер не пропускает IPv6 — закрыл IPv6 в туннеле, приложения идут по IPv4 (до следующего подключения)")
                    } catch (e: Throwable) {
                        ipv6Auto = null
                        storage.log("VPN: не удалось закрыть IPv6: ${e.message}")
                    }
                }
            }
        }

        // 8) Вариант А: цель идёт через свой сервер, а сервер мог отвалиться. Раз в несколько минут проверяем его и, если не работает,
        //    ставим следующий из запасных (без этого приложение оставалось бы без связи до ближайшей фоновой перепроверки).
        jobs += scope.launch {
            delay(ROUTE_WATCH_FIRST_MS)
            while (true) {
                try {
                    watchRoutes()
                    delay(ROUTE_WATCH_PERIOD_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    storage.log("ERROR: проверка маршрутов целей: ${e.javaClass.simpleName}: ${e.message}")
                    delay(ROUTE_WATCH_PERIOD_MS)
                }
            }
        }

        // 4) Скорость: если интернет «есть», но через сервер почти ничего не качается — сменить сервер.
        jobs += scope.launch {
            while (true) {
                try {
                    delay(SPEED_PERIOD_MS)
                    watchSpeed()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    storage.log("ERROR: проверка скорости: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        monitors = jobs
        registerNetwork()
        registerScreen()
    }

    private fun stopMonitors() {
        monitors.forEach { it.cancel() }
        monitors = emptyList()
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        netCallback = null
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
        idleReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        idleReceiver = null
    }

    private fun registerNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                // первый вызов — обычно уже существующая сеть; но если служба стартовала без сети, это как раз её появление — тогда не пропускаем
                if (first) { first = false; if (NetInfo.label(this@PhoenixVpnService) == lastNetLabel) return }
                scope.launch {
                    delay(1_000) // дать сети устояться
                    onNetworkChanged()
                }
            }

            // ушла Wi-Fi (или мобильная): телефон перешёл на оставшуюся сеть, onAvailable для неё не вызывается — иначе заметили бы только через 5 минут
            // система предупреждает, что сеть скоро пропадёт: не считаем сбои маршрутов и не начинаем тяжёлое
            override fun onLosing(network: Network, maxMsToLive: Int) {
                disturbedAt = System.currentTimeMillis()
                storage.log("NET: система предупредила, что сеть пропадёт (через ~${maxMsToLive / 1000} с) — жду смены сети")
            }

            override fun onLost(network: Network) {
                scope.launch {
                    delay(1_500)
                    onNetworkChanged()
                }
            }
        }
        cm.registerNetworkCallback(request, cb)
        netCallback = cb
    }

    private fun registerScreen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try { ActivityStore.get(this@PhoenixVpnService).recordActive() } catch (_: Throwable) {}
                scope.launch {
                    delay(1_000)
                    checkHealthOnce()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, filter)
        screenReceiver = r
        // телефон вышел из глубокого сна: сокеты ядра могли зависнуть — проверяем туннель сразу, а не по таймеру
        val idle = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isDeviceIdleMode) scope.launch { delay(1_500); checkHealthOnce() }
            }
        }
        val idleFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(idle, idleFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(idle, idleFilter)
        idleReceiver = idle
    }

    /** Сменилась сеть (Wi-Fi ↔ мобильная): проверить туннель и, если нужно, заново определить тип блокировок. */
    @Volatile private var lastNetLabel = ""

    /** Когда в последний раз менялись сеть, режим или ядро: сразу после этого проверки целей дают ложные «не отвечает». */
    @Volatile private var disturbedAt = 0L

    /** Последний запуск определения режима по смене сети: при двух сменах подряд (wifi → none → mobile) второй отменяет первый, а не бежит параллельно с ним. */
    @Volatile private var detectJob: Job? = null

    private suspend fun onNetworkChanged() {
        val label = NetInfo.label(this)
        val changed = label != lastNetLabel
        if (changed) storage.log("NET: сеть сменилась: ${lastNetLabel.ifEmpty { "?" }} → $label")
        lastNetLabel = label
        // адреса серверов, найденные в прежней сети, в новой могут быть другими
        if (changed) ServerHosts.clear()
        if (changed && NetInfo.kind(this) != NetInfo.Kind.NONE) {
            // сеть появилась: если плановый сбор был пропущен из-за её отсутствия, запускаем сразу, а не ждём будильника
            try { Scheduler.catchUpIfNeeded(applicationContext, "network") } catch (_: Throwable) {}
        }
        if (changed) {
            disturbedAt = System.currentTimeMillis()
            routeFails.clear()
            // ручной выбор режима был сделан в прежней сети; вернувшись в сеть с тем же названием, он не должен заглушать определение (в логе — 13 минут без связи)
            if (settings.manualModeAt > 0L) { settings.manualModeAt = 0L; storage.log("MODE: сеть сменилась — ручной выбор режима больше не удерживается, определение снова решает само") }
        }
        // тип блокировок определяется сразу и параллельно с проверкой связи (раньше ждал её окончания — до 20 секунд),
        // а прежние голоса относились к другой сети: при смене сети хватает одного уверенного определения
        if (changed && NetInfo.kind(this) != NetInfo.Kind.NONE) {
            // уход в «нет сети» — это не блуждание между сетями, определять по нему нечего, и в счётчик «меняется часто» не включаем
            val nowF = System.currentTimeMillis()
            flap.note(nowF)
            val flapping = flap.flapping(nowF)
            if (flapping) NetFlap.mark(nowF)
            if (flapping) storage.log("NET: сеть меняется часто (${flap.count(nowF)} раз за ${FlapGuard.WINDOW_MS / 60_000} мин) — тип блокировок подтверждаю дольше, одного определения мало")
            // смена сети следом за сменой (wifi → none → mobile — это уже две смены) не должна запускать два
            // параллельных определения: отменяем прежнее и запускаем заново
            detectJob?.cancel()
            detectJob = scope.launch { detectMode(force = true, decisive = !flapping) }
        }
        // новая сеть — что в ней открывается без VPN, неизвестно: проверяем сразу (после устаканивания сети), а не при следующей перепроверке
        if (changed) scope.launch {
            delay(8_000)
            try { withContext(Dispatchers.IO) { DirectProbe.run(this@PhoenixVpnService, storage, onlyDue = true) } } catch (e: Exception) { storage.log("BYPASS: ошибка проверки в новой сети: ${e.message}") }
        }
        // старые сокеты ядра остались на ушедшей сети и висят 10–15 секунд: если связи нет, пересоздаём их тем же сервером, не меняя сервер
        if (changed && homeLink == null && controller?.isRunning == true && !healthy()) {
            val ok = opLock.withLock {
                if (!wantRunning) false else try {
                    withContext(Dispatchers.IO) { reloadCore(mainLink) }
                    true
                } catch (e: Throwable) {
                    storage.log("NET: не удалось пересоздать сокеты ядра: ${e.message}")
                    false
                }
            }
            if (ok) { storage.log("NET: сеть сменилась, связи не было — пересоздал сокеты ядра тем же сервером [$mainTitle]"); delay(1_500) }
        }
        checkHealthOnce(force = true)
        if (!changed) detectMode()
    }

    /**
     * Определение типа блокировок по тому, что открывается напрямую. Режим меняется после двух одинаковых определений подряд;
     * если первое показало другой тип, второе делается сразу (через 3 секунды), а не при следующем событии — иначе
     * режим «застревал» на чужом и в чёрных списках показывались серверы белых, и наоборот.
     */
    private suspend fun detectMode(force: Boolean = false, decisive: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!settings.autoMode || (!force && now - lastDetectAt < DETECT_GAP_MS)) return
        lastDetectAt = now
        var outcome = withContext(Dispatchers.IO) { ModeDetector.run(this@PhoenixVpnService, if (decisive) 1 else ModeDecider.NEED_IN_ROW) }
        if (decisive) {
            // сеть ещё устаивается («нет сети», «не ясно»): повторяем через несколько секунд, а не ждём 5 минут
            var retries = 0
            while (ModeDecider.modeOf(outcome.type) == null && retries < 3 && wantRunning) {
                retries++
                delay(4_000)
                outcome = withContext(Dispatchers.IO) { ModeDetector.run(this@PhoenixVpnService, 1) }
            }
        }
        val target = ModeDecider.modeOf(outcome.type)
        if (outcome.switchedTo == null && target != null && target != storage.mode) {
            // «белые списки» из чёрных — самый дорогой ложный вывод (сбой Wi-Fi даёт «0 зарубежных»): подтверждаем дольше, настоящие белые списки не пропадают за минуту
            delay(if (target == Mode.WHITELIST && !decisive) WHITELIST_CONFIRM_MS else 3_000)
            outcome = withContext(Dispatchers.IO) { ModeDetector.run(this@PhoenixVpnService) }
        }
        if (outcome.switchedTo != null) PlanBus.notifyChanged() // режим сменился — данные теперь из другой папки
    }

    /** Туннель после смены сервера: связь есть два раза подряд (один удачный ответ может быть случайным). */
    private suspend fun tunnelWorks(): Boolean {
        delay(1_200)
        if (!healthy()) return false
        delay(1_000)
        return healthy()
    }

    @Volatile private var statLast = 0L

    /** Учитывает время работы VPN на текущем сервере (вызывать перед сменой названия сервера, при проверках и при отключении). */
    private fun statTick(now: Long = System.currentTimeMillis()) {
        val last = statLast
        if (last <= 0L) return
        val delta = now - last
        if (delta < 1000L) return
        try { UsageStats.of(storage.statsFile).addConnected(mainTitle, delta / 1000L, java.time.LocalDate.now().toString(), now) } catch (_: Throwable) { }
        statLast = last + delta / 1000L * 1000L
    }

    private fun stat(key: String) {
        try { UsageStats.of(storage.statsFile).add(key, 1, java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) { }
    }

    private fun energy(kind: String, count: Int = 1) {
        try { EnergyMeter.of(java.io.File(filesDir, "energy.tsv")).add(kind, count, java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    private suspend fun healthy(): Boolean = withContext(Dispatchers.IO) {
        energy("health")
        val port = healthPort
        if (port <= 0) return@withContext controller?.isRunning == true
        if (controller?.isRunning != true) return@withContext false
        val auth = healthAuth ?: return@withContext false
        val detail = ArrayList<String>()
        for (url in HEALTH_URLS) {
            val host = url.substringAfter("://").substringBefore('/')
            try {
                LocalSocks.get(port, auth, url, connectTimeoutMs = 5_000, readTimeoutMs = 5_000).use { r ->
                    if (HealthVerdict.ok(url, r.code)) {
                        healthDetail = ""
                        return@withContext true
                    }
                    detail += "$host: HTTP ${r.code}"
                }
            } catch (_: Exception) {
                detail += "$host: ${LocalSocks.lastError ?: "ошибка"}"
            }
        }
        healthDetail = detail.joinToString("; ")
        false
    }

    private val flight = SingleFlight()

    /** Проверка туннеля «одна за раз»: экран, смена сети и таймер в одну секунду не запускают три одинаковые проверки. */
    private suspend fun checkHealthOnce(force: Boolean = false) {
        if (!flight.tryEnter("health")) return
        statTick()
        try {
            checkHealth(force)
        } finally {
            flight.leave("health")
        }
    }

    @Volatile private var lastBusySkipLogAt = 0L

    private suspend fun checkHealth(force: Boolean = false) {
        if (!wantRunning) return
        val genBefore = coreGen
        if (opLock.isLocked) {
            // идёт подключение, смена сервера или перезапуск ядра: проверка в этот момент ловит «ядро не работает»/ECONNREFUSED — это не отказ сервера (журнал 06.11 06:08)
            val now = System.currentTimeMillis()
            if (now - lastBusySkipLogAt > 60_000L) { lastBusySkipLogAt = now; storage.log("HEALTH: идёт смена сервера или перезапуск ядра — проверку пропускаю, результат был бы ложным") }
            return
        }
        if (healthy()) {
            if (failures > 0 || VpnState.status.value.note.isNotEmpty()) {
                if (failures > 0) storage.log("HEALTH: связь через [$mainTitle] восстановилась (неудач было $failures), сеть ${NetInfo.label(this)}")
                failures = 0
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
            if (ok) { failures = 0; delay(1_500); return }
        }
        if (failures == 1) {
            // отменяем прежний запуск (например, из onNetworkChanged) и запускаем новый определённо forced: иначе при
            // совпадении по времени прежний уже успевает выставить lastDetectAt, и этот сразу выходит по DETECT_GAP_MS,
            // ничего не определив — а первая проверка связи после смены сети и есть самый частый такой случай (находка Фейбла №3.1)
            detectJob?.cancel()
            detectJob = scope.launch { detectMode(force = true) }
        } // связь пропала: возможно, сменился тип блокировок
        if (failures < (if (force) 2 else 3)) {
            // Подтвердим быстрее: следующая проверка через несколько секунд.
            delay(4_000)
            checkHealth(force = true)
            return
        }
        if (!settings.autoReconnect) {
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи через этот сервер"), link = mainLink))
            return
        }
        // нет сети вообще (самолёт, Wi-Fi без доступа) — перебирать серверы бессмысленно, только раскручивает ядро
        if (NetInfo.kind(this) == NetInfo.Kind.NONE) {
            failures = 0
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи: у телефона нет сети"), link = mainLink))
            return
        }
        // система считает сеть живой, но напрямую (мимо VPN) не открывается ничего — значит, интернета нет, а не сервер плох
        // (в логе: три поиска по 2 минуты с нулём рабочих, пока у телефона не было интернета)
        val offline = ModeDetector.offlineAt.let { FailoverGate.offlineRecently(it, System.currentTimeMillis()) } ||
            withContext(Dispatchers.IO) { BlockDetector.classify(ModeDetector.measure(this@PhoenixVpnService)) == BlockType.OFFLINE }
                .also { if (it) ModeDetector.offlineAt = System.currentTimeMillis() }
        if (offline) {
            failures = 0
            GovernedLimits.get(storage).let { g ->
                val n = NetInfo.label(this)
                if (g.outage(n, System.currentTimeMillis())) {
                    g.save()
                    storage.log("HEALTH: интернет у сети $n пропал сразу после тяжёлой проверки — запомнил, в этой сети буду делать меньше параллельных проверок")
                }
            }
            storage.log("HEALTH: прямая проверка тоже ничего не открыла (нет интернета у самой сети ${NetInfo.label(this)}) — серверы не перебираю, повторю при появлении сети")
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи: у сети нет интернета"), link = mainLink))
            return
        }
        val modeHeld = ModeHold.active(settings.manualModeAt, settings.manualModeNet, System.currentTimeMillis(), NetInfo.label(this))
        if (settings.autoMode && !modeHeld && FailoverGate.modePending(storage.mode, ModeDetector.lastType, ModeDetector.lastTypeAt, System.currentTimeMillis())) {
            failures = 0
            storage.log("HEALTH: определение показывает «${BlockDetector.title(ModeDetector.lastType!!)}», а режим пока «${storage.mode.title}» — жду подтверждения смены режима, серверы не перебираю")
            return
        }
        if (manualPinLink == mainLink && controller?.isRunning == true) {
            failures = 0
            storage.log("HEALTH: [$mainTitle] выбран вручную — не меняю сам (общая проверка может не проходить, например у telegram-only); сменить можно кнопкой «Сменить сервер»")
            if (pinFailSince == 0L) pinFailSince = System.currentTimeMillis()
            val minutes = (System.currentTimeMillis() - pinFailSince) / 60_000
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = "нет связи по общей проверке${if (minutes >= 3) " уже $minutes мин" else ""} — сервер выбран вручную, не меняю" + (if (minutes >= 3) tr("; если не работает, нажмите «Сменить сервер»") else ""), link = mainLink))
            nextFailoverAt = System.currentTimeMillis() + 5 * 60_000L
            return
        }
        if (FailoverGate.modeSettling(modeSwitchedAt, System.currentTimeMillis())) {
            failures = 0
            storage.log("HEALTH: режим только что сменился — даю серверу устояться, перебор отложен")
            return
        }
        recordHist(mainLink, false) // сервер перестал работать именно в этой сети
        Quarantine.add(ConfigIdentity.key(mainLink)) // 30 минут не выбираем его снова
        if (System.currentTimeMillis() < nextFailoverAt) {
            failures = 0 // недавно уже перебирали и ничего не нашли — не мучаем сеть и батарею
            return
        }
        failover()
    }

    /** Серверы, подходящие открытому приложению/сайту (для проверки запасного): null — контекста нет, ограничений нет. */
    @Volatile private var appFit: Set<String>? = null

    /** Контекст, под который сейчас идёт смена сервера по DIAG (не меняется, если пользователь успел открыть другое приложение). */
    @Volatile private var diagCtx: AppContext.Now? = null

    /**
     * Учитывает, что открыто сейчас (приложение или сайт в браузере; если открыт сам Phoenix или рабочий стол — то, чем пользовались последние 20 минут):
     * серверы этой цели первыми, затем серверы других недавних приложений, затем остальные; где сервис не открывался — после них,
     * а серверы с выходом в закрытой для сервиса стране (по замеру) — в самом конце.
     */
    private fun appAwareOrder(list: List<Candidate>, force: Boolean = false): List<Candidate> {
        appFit = null
        if (!force && !settings.appAwareFailover) return list
        val targets = TargetStore(storage.targetsFile).load()
        val ctx = diagCtx ?: try { AppContext.now(this, targets) } catch (_: Throwable) { null } ?: return list
        // без своей цели (находка Фейбла №2, тот же случай, что и в diagGoogle) приложение Gemini/NotebookLM иначе
        // вообще не участвует в этой упорядочке — DIAG мог бы отметить сервер запретным, но veto.active() здесь
        // никогда не сработает без адреса, по которому его искать
        val checkUrl = ctx.checkUrl ?: GoogleDiag.fallbackCheckUrl(ctx.pkg, ctx.site)
        if (checkUrl == null && ctx.others.isEmpty()) return list
        val primary = ctx.target?.servers.orEmpty().filter { it != Target.DIRECT }.map { ConfigIdentity.key(it) }.toSet()
        val others = ctx.others.flatMap { it.servers }.filter { it != Target.DIRECT }.map { ConfigIdentity.key(it) }.toSet()
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
            val (auto, fixed) = arranged.partition { ServerKind.isAuto(LinkParser.parse(it.link)?.title?.let { t -> urlDecode(t) }.orEmpty()) }
            // серверы белых списков (в чёрных они обычно в самом конце очереди) подмешиваем: после каждых двух своих один белый
            val (white, own) = fixed.partition { it.foreign || it.mismatch }
            if (storage.mode == Mode.BLACKLIST && white.isNotEmpty()) {
                storage.log("FAILOVER: для ${ctx.logLabel} закрыты многие обычные серверы — подмешиваю серверы белых списков (${white.size}) через каждые два своих")
                QueueMix.mix(own, white, 2) + auto
            } else fixed + auto
        } else arranged
        appFit = out.filter { c ->
            val t = AppFit.tier(c, primary, others, exits[CheckStore.keyOf(c.link)]?.cc, blocked, vetoed)
            t <= 1 || (t == 2 && (svc == null || ServiceMatch.groupOf(c, checks, svc) <= 1))
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

    /** Опыт приложения: конфиг [link] в текущей сети сработал или нет (и с какой скоростью). Ничего не проверяет сам. */
    private val flap = FlapGuard()

    @Volatile private var diagAt = 0L
    @Volatile private var diagRunning = false
    private val autoVeto = AutoVetoGate()
    @Volatile private var diagLastKey = ""

    /**
     * Пока открыт Gemini или NotebookLM (приложение или сайт), раз в 2 минуты (и сразу после смены сервера) подробно проверяет через текущий сервер
     * сам сервис, вход в аккаунт и проверку связи Google; результат — строки `DIAG:`. Ничего не делает, если открыто другое.
     */
    private fun diagGoogle(reason: String) {
        if (diagRunning || !wantRunning) return
        val now = System.currentTimeMillis()
        val ctx = try { AppContext.now(this, TargetStore(storage.targetsFile).load()) } catch (_: Throwable) { null }
        if (ctx == null || !GoogleDiag.relevant(ctx.pkg, ctx.site)) return
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
                storage.log("DIAG: открыто ${ctx.logLabel}, сервер [$title], сеть ${NetInfo.label(this@PhoenixVpnService)}, режим ${storage.mode.title} ($reason)")
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
                val closedKey = if (want == "gemini") "notebook" else want
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
                    } else if (manualPinLink == diagLink) {
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
                        try { failover(forced = true, smart = true, verifyUrl = verifyUrl) } finally { diagCtx = null }
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

    /** Когда началась потеря связи из-за смены режима/сервера (0 — перерыва нет); для строки DOWNTIME в журнале. */
    @Volatile private var outageAt = 0L

    private fun openOutage() {
        val now = System.currentTimeMillis()
        // прежний перерыв так и не закрылся (сервер не нашёлся или сети не было): считаем заново, иначе он приплюсуется к следующему (журнал 07.11: «перерыв 2331 с»)
        if (outageAt == 0L || now - outageAt > DowntimeStats.MAX_SEC * 1000L) outageAt = now
    }

    private fun closeOutage(kind: String) {
        val start = outageAt
        outageAt = 0L
        if (start <= 0L) return
        val sec = ((System.currentTimeMillis() - start) / 1000L).toInt()
        try {
            val day = java.time.LocalDate.now().toString()
            val st = DowntimeStats.of(java.io.File(storage.base, "downtime.tsv"))
            if (st.add(kind, sec, day)) storage.log("DOWNTIME: связи не было $sec с (${DowntimeStats.kindTitle(kind)}). За сегодня: ${st.summary(day)}")
            else storage.log("DOWNTIME: перерыв $sec с — дольше ${DowntimeStats.MAX_SEC / 60} мин, в учёт не беру (похоже, интернета не было совсем)")
        } catch (_: Throwable) {
        }
    }

    /** Запоминает, что этот сервер работал в текущей сети и режиме: при следующей смене режима он пробуется первым. */
    private fun rememberGood(link: String) {
        try { LastGoodStore.of(java.io.File(storage.base, "lastgood.tsv")).remember(NetInfo.label(this), storage.mode.name, link, System.currentTimeMillis()) } catch (_: Throwable) { }
    }

    /**
     * Быстрые варианты проверяются сразу все, отдельными экземплярами ядра, не трогая туннель. Раньше они шли по одному с перезапуском
     * ядра на каждом (журнал 06.11: 34, 102, 171 и 51 с, всего 6 минут без результата).
     * @return варианты, ответившие на проверку, либо null, если предпроверка не удалась (тогда работает прежний перебор)
     */
    private suspend fun prefilterPicks(picks: List<Pick>): List<Pick>? {
        if (picks.isEmpty()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val gate = Semaphore(MODE_PREFILTER_PARALLEL)
                coroutineScope {
                    picks.map { p ->
                        async {
                            gate.withPermit {
                                val parsed = LinkParser.parse(p.link)
                                var hosts: Map<String, String> = emptyMap()
                                if (parsed?.host != null && parsed.port != null) {
                                    val ip = if (parsed.protocol == "hysteria2") ServerHosts.resolveOnly(parsed.host) else ServerHosts.reach(parsed.host, parsed.port, SEARCH_TCP_TIMEOUT_MS)
                                    if (ip == null) return@withPermit null
                                    hosts = ServerHosts.hostsFor(parsed.host, ip)
                                }
                                val cfg = try { XrayConfig.probeConfig(p.link, hosts) } catch (_: UnsupportedLinkException) { return@withPermit null }
                                val ms = ProbeShare.measure(cfg, CheckServices.BASELINE_URL) { XrayCore.measureDelay(this@PhoenixVpnService, cfg, CheckServices.BASELINE_URL) }
                                if (ms >= 0) p else null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
        } catch (e: Throwable) {
            storage.log("MODE: предпроверка быстрых вариантов не удалась (${e.message}) — иду по одному")
            null
        }
    }

    private fun recordHist(link: String, ok: Boolean, mbps: Double = -1.0) {
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
    /** Название открытого сейчас приложения из целей (или пусто) — для умной смены. */
    private fun openAppLabel(): String {
        val ctx = try { AppContext.now(this, TargetStore(storage.targetsFile).load()) } catch (_: Throwable) { null } ?: return ""
        return ctx.label
    }

    /**
     * «Умная смена»: не ждёт отказа по таймеру, а сама разбирается. Принудительно проверяет сеть напрямую и туннель, и если
     * проблема в сервере (или вы просите лучший) — подбирает сервер под открытое сейчас приложение. Если нет сети или у сети
     * нет интернета, сервер не меняет и говорит об этом.
     */
    private suspend fun smartSwitch() {
        val app = openAppLabel()
        val net = NetInfo.label(this)
        storage.log("SMART: умная смена, открыто: ${app.ifBlank { "неизвестно" }}, сеть $net, режим ${storage.mode.title}")
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("проверяю сеть и туннель…"), link = mainLink))
        val none = NetInfo.kind(this) == NetInfo.Kind.NONE
        val offline = !none && withContext(Dispatchers.IO) { BlockDetector.classify(ModeDetector.measure(this@PhoenixVpnService)) == BlockType.OFFLINE }
        val works = !none && !offline && withContext(Dispatchers.IO) { try { healthy() } catch (_: Throwable) { false } }
        val v = SmartVerdict.decide(none, offline, works, app)
        storage.log("SMART: вывод: ${v.text}")
        if (!v.switchServer) {
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("умная смена: {}", v.text), link = mainLink))
            showForeground(mainTitle.ifBlank { "Phoenix" }, "Умная смена: ${v.text}")
            return
        }
        smartVerdictText = v.text
        failover(forced = true, smart = true)
    }

    /**
     * Общая часть ручной кнопки «не открывается» и автоматического DIAG: сервер исключается для сайта на 3 дня (`ServiceVeto`),
     * выбранному сервису у этого сервера в `CheckStore` ставится «не открылся» (чтобы и фоновые проверки это видели),
     * и сервер убирается из списка серверов цели (если был там). Раньше это делала только ручная кнопка — у автоматического
     * DIAG была только пометка в ServiceVeto, которую живой выбор следующего сервера (appAwareOrder) тогда ещё не читал.
     */
    private fun markServiceClosed(link: String, url: String, targets: List<Target>) {
        ServiceVeto(storage.vetoFile).add(link, url)
        val key = CheckStore.keyOf(link)
        val services = storage.checkSelection().selected()
        val svc = ServiceMatch.serviceFor(url, services)
        val store = CheckStore(storage.checksFile)
        if (svc != null) store.modify(recordOdds = false) { cur ->
            cur[key]?.let { old -> mapOf(key to old.copy(services = old.services + (svc.id to CheckServices.FAILED))) } ?: emptyMap()
        }
        // цель не должна держать этот сервер среди своих
        val id = ConfigIdentity.key(link)
        for (t in targets) if (t.servers.any { it != Target.DIRECT && ConfigIdentity.key(it) == id }) {
            TargetStore(storage.targetsFile).updateServers(t.id, t.servers.filter { it == Target.DIRECT || ConfigIdentity.key(it) != id }, t.verifiedAt)
        }
    }

    /**
     * Кнопка «Сервис не открывается на этом сервере»: запоминает, что на текущем сервере открытый сейчас сайт или приложение не работает
     * (приложение само этого не поняло — страница «не поддерживается в вашей стране» приходит с кодом 200), и подбирает другой сервер.
     */
    private suspend fun vetoCurrent() {
        val targets = TargetStore(storage.targetsFile).load()
        val ctx = try { AppContext.now(this, targets) } catch (_: Throwable) { null }
        val url = ctx?.checkUrl
        if (ctx == null || url == null) {
            storage.log("VETO: не знаю, какой сервис открыт (нужен доступ к статистике использования или служба «сайт в браузере») — сервер не помечаю")
            VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("не знаю, какой сервис открыт — откройте его и нажмите снова"), link = mainLink))
            return
        }
        val link = mainLink
        // что на самом деле показывает сайт через этот сервер — в журнал: по нему можно подобрать признаки страницы-заглушки
        withContext(Dispatchers.IO) {
            val port = LocalProxy.port
            val auth = LocalProxy.auth
            if (port > 0 && auth != null) {
                try {
                    LocalSocks.get(port, auth, url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000, follow = true, userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").use { r ->
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
        failover(forced = true, smart = true)
    }

    @Volatile private var smartVerdictText = ""

    /** [verifyUrl] — сайт/сервис с проверкой региона, который только что был закрыт на прежнем сервере (DIAG): кандидат проверяется
     * и на него, прежде чем смена засчитывается, иначе «подключился» и «сервис открылся» — разные вещи, а следующий DIAG
     * узнает о повторном провале только через 2 минуты новым прогоном. */
    private suspend fun failover(forced: Boolean = false, smart: Boolean = false, verifyUrl: String? = null) = opLock.withLock {
        if (!wantRunning) return@withLock
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
        Quarantine.add(ConfigIdentity.key(origLink))
        val origTitle = mainTitle
        val currentId = ConfigIdentity.key(origLink)
        val currentHost = ConfigIdentity.host(origLink)
        val started = System.currentTimeMillis()
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
                VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи, запасных конфигов нет"), link = mainLink))
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
        VpnState.set(VpnStatus(VpnPhase.CONNECTING, mainTitle, note = if (forced) tr("меняю сервер…") else tr("ищу рабочий сервер среди {}…", ordered.size), link = mainLink))
        val sink = SearchSink(storage, this)
        val gnet = NetInfo.label(this)
        val spareLink = SpareServer.take(gnet, System.currentTimeMillis(), currentId)
        val fit = appFit
        val spareCand = spareLink?.let { l -> ordered.firstOrNull { it.link == l } }
        // запасной, найденный заранее, подбирался без учёта приложения: берём его, только если он подходит открытому сейчас приложению
        if (spareCand != null && fit != null && spareCand.identity !in fit) storage.log("FAILOVER: запасной найденный заранее не подходит открытому приложению — ищу под него")
        // «Auto» — выбор самой подписки, страна выхода у него плавает: для сервисов с проверкой региона (Gemini, NotebookLM) запасным не берём
        // (в журнале: после «Notebook закрыт» запасным шёл Auto и сразу же оказывался таким же закрытым)
        val spareIsAutoForGeo = spareCand != null && verifyUrl != null && GeoGate.applies(verifyUrl) &&
            ServerKind.isAuto(LinkParser.parse(spareCand.link)?.title?.let { t -> urlDecode(t) }.orEmpty())
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
                if (!ok) Quarantine.add(f.cand.identity) // ответил поиску, но через туннель связи нет: не возвращаемся к нему сразу
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
                failoverBackoffMs = FAILOVER_BACKOFF_START_MS
                homeLink = null // прежний основной сервер не работает — возвращаться на него не нужно
                switchedPkg = ""
                setConnected(SwitchReason.note(if (forced) "сервер сменён" else "нашёл рабочий сервер за ${(System.currentTimeMillis() - started) / 1000} с", switchReason))
                if (forced) storage.log("SWITCH: сервер сменён [$origTitle] → [$mainTitle] за ${(System.currentTimeMillis() - started) / 1000} с")
                storage.log("VPN: выход нового сервера: " + (ExitStore(storage.exitFile).load()[CheckStore.keyOf(f.cand.link)]?.cc?.let { "$it (проверено)" } ?: "не проверен (страна по названию: ${f.cand.cc.ifEmpty { "?" }})"))
                storage.log("VPN: переключился на запасной [$mainTitle] (${msText(f.ms)}, поиск ${(System.currentTimeMillis() - started) / 1000} с, проверено ${sink.count}; ${sink.statsText()})")
                return true
            }
        }
        return false
        }
        if (spareFound.isNotEmpty()) {
            storage.log("FAILOVER: пробую запасной сервер, найденный заранее")
            if (tryFound(spareFound)) return@withLock
        }
        var found = withContext(Dispatchers.IO) { searchFor(ordered, want = 2, budgetMs = FAILOVER_BUDGET_MS, sink = sink) }
        sink.flush()
        energy("search", sink.count)
        // никто из остальных не отвечает: серверы из карантина получают второй шанс первыми
        val parked = ordered.filter { it.quarantined }
        if (found.isEmpty() && parked.isNotEmpty() && wantRunning) {
            Quarantine.releaseAll()
            storage.log("FAILOVER: остальные не отвечают — снимаю карантин и проверяю первыми ${parked.size} серверов из него")
            found = withContext(Dispatchers.IO) { searchFor(parked, want = 2, budgetMs = FAILOVER_BUDGET_MS / 3, sink = sink) }
            sink.flush()
        }
        found.drop(1).firstOrNull { fit == null || it.cand.identity in fit }?.let { SpareServer.remember(it.cand.link, it.cand.identity, gnet, System.currentTimeMillis()) }
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
        failoverBackoffMs = (failoverBackoffMs * 2).coerceAtMost(FAILOVER_BACKOFF_MAX_MS)
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("нет связи — повторю позже"), link = mainLink))
        storage.log("VPN: рабочий сервер не найден (проверено ${sink.count} из ${ordered.size} за ${(System.currentTimeMillis() - started) / 1000} с; ${sink.statsText()})")
        if (FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis())) storage.log("VPN: похоже, интернета нет совсем — полную перепроверку не запускаю, повторю позже")
        else if (Recheck.enqueueIfDue(this, storage.mode, urgent = true)) storage.log("VPN: запустил полную перепроверку всех конфигов")
    }

    /**
     * Ручной выбор сервера из списка: переключаемся на него, проверяем связь (дважды подряд) и при неудаче возвращаем прежний.
     */
    private suspend fun switchTo(link: String, title: String) = opLock.withLock {
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
            failoverBackoffMs = FAILOVER_BACKOFF_START_MS
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
    private suspend fun searchFor(ordered: List<Candidate>, want: Int, budgetMs: Long, sink: SearchSink): List<Found> {
        val gov = GovernedLimits.get(storage)
        val net = NetInfo.label(this)
        if (gov.level(net) > 0) storage.log("SEARCH: сеть $net не любит много параллельных проверок — иду осторожнее (уровень ${gov.level(net)}, TCP ${gov.tcp(net)})")
        gov.begin(net)
        val search = ServerSearch(
            reach = { h, p -> ServerHosts.reach(h, p, SEARCH_TCP_TIMEOUT_MS) },
            probe = { cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(this, cfg, url) } },
            tcpParallel = ParallelGovernor.capFor(gov.tcp(net), NetInfo.kind(this) == NetInfo.Kind.MOBILE),
            probeParallel = gov.probe(net, CheckLimits.searchParallel(this)),
            maxCandidates = 3_000,
            loadSample = GatewayProbe.sampler(this),
            // «Отключить» или отзыв VPN не должны ждать конца поиска (до 2 минут) — он обрывается на первой же проверке.
            shouldStop = { !wantRunning },
        )
        return search.find(ordered, want, budgetMs) { c, ms -> sink.add(c, ms) }.also {
            if (search.throttled > 0) storage.log("SEARCH: роутер отвечал медленно — притормаживал проверки ${search.throttled} раз(а), чтобы не перегружать сеть")
            if (!search.suspectedOffline && gov.finish(net, System.currentTimeMillis())) storage.log("SEARCH: сеть $net выдержала несколько проверок — снимаю ограничение на один уровень")
            gov.save()
            if (search.suspectedOffline) {
                if (gov.outage(net, System.currentTimeMillis())) storage.log("SEARCH: сеть $net потеряла интернет во время проверок — в следующий раз параллельных проверок будет меньше")
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
    private suspend fun applyModeIfChanged() {
        val needSearch = opLock.withLock { switchToModeServer() }
        if (needSearch) {
            if (wantRunning) failover()
        }
    }

    /** @return true — быстрые попытки не удались, нужен общий поиск (вызывается уже без блокировки). */
    private suspend fun switchToModeServer(): Boolean {
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
        val allPicks = withContext(Dispatchers.IO) { QuickConnect.picksFor(this@PhoenixVpnService, MODE_QUICK_TRIES) }
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
            // связь есть — теперь можно тратить силы на остальное: цели и перепроверка нового режима
            TargetsWorker.enqueue(this)
            Recheck.enqueueIfDue(this, mode)
            return false
        }
        storage.log("MODE: из ${picks.size} быстрых вариантов ни один не работает — ищу среди всех конфигов режима «${mode.title}»")
        return true
    }

    // ---- подготовка серверов к приложениям по часам их открытия ----

    private fun appTargets(): Map<String, Target> =
        TargetStore(storage.targetsFile).load().filter { it.kind == TargetKind.APP && it.servers.isNotEmpty() && !it.isDirect }.associateBy { it.value }

    /** Запоминает, в какие часы недели открывают приложения из целей (по данным об использовании). */
    private fun recordAppUse() {
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
    private suspend fun prepareApps() {
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
                    ProbeShare.measure(cfg, t.checkUrl) { XrayCore.measureDelay(this@PhoenixVpnService, cfg, t.checkUrl) } >= 0 || SpeedRunner.reaches(this@PhoenixVpnService, link, t.checkUrl)
                } catch (_: Throwable) { false }
            }
            storage.log("ACT: «${t.label}» обычно открывают в этот час — сервер для него " + if (alive) "отвечает" else "не отвечает, запускаю перепроверку целей")
            if (!alive) TargetsWorker.enqueue(this)
        }
    }

    // ---- низкая скорость ----

    private val snapshotUid = android.os.Process.myUid()
    private var speedProbeFailedLogged = false
    @Volatile private var speedErr = ""

    /**
     * Раз в 45 секунд, пока экраном пользуются: смотрим, сколько данных прошло через приложение (оно только и несёт туннель).
     * Если трафика почти нет — ничего не делаем и ничего не качаем. Если трафик есть, но медленный, делаем один небольшой
     * замер (до 400 КБ) и после нескольких медленных подряд пробуем другие серверы.
     */
    private suspend fun watchSpeed() {
        if (!wantRunning || !settings.slowSwitch || controller?.isRunning != true || healthPort <= 0) return
        if (homeLink != null || failures > 0) return // идёт переключение для приложения или проверка связи
        if (manualPinLink == mainLink) return // сервер выбран вручную — по скорости его не меняем
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        if (withContext(Dispatchers.IO) { Focus.active(this@PhoenixVpnService) }) { shape.reset(); return } // «Фокус»: открыто приложение из целей
        val rx = android.net.TrafficStats.getUidRxBytes(snapshotUid)
        val tx = android.net.TrafficStats.getUidTxBytes(snapshotUid)
        if (rx < 0 || tx < 0) return // система не отдаёт счётчики
        val prevRx = lastRx
        val prevTx = lastTx
        lastRx = rx
        lastTx = tx
        if (prevRx < 0) return
        val moved = (rx - prevRx) + (tx - prevTx)
        if (moved < ACTIVE_BYTES) {
            slow.reset() // телефон почти не используют: нечего измерять
            shape.reset()
            return
        }
        // реальный трафик и так идёт быстрее порога — сервер не медленный, замер не нужен
        val rate = SlowMonitor.passiveRateMbps(moved, SPEED_PERIOD_MS / 1000.0)
        slow.minMbps = settings.slowMinMbps.toDouble()
        // шейпинг оператора: скорость упала в 2–3 раза после нескольких минут нормальной; подтверждаем отдельным замером
        if (shape.feed(rate) && slow.canSwitch(System.currentTimeMillis())) {
            val peak = shape.peak
            val check = withContext(Dispatchers.IO) { probeSpeed() }
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
     * Пропускает ли сервер IPv6: запрос к адресу Cloudflare по IPv6 через внутренний SOCKS ядра.
     * @return true/false, либо null — вывод сделать нельзя (нет и IPv4: тогда виноват не IPv6)
     */
    private fun ipv6Verdict(): Boolean? {
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
    private fun probeSpeed(): Double? {
        energy("speed")
        val port = healthPort
        if (port <= 0) return null
        val auth = healthAuth ?: return null
        speedErr = ""
        for (url in SPEED_URLS) {
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
    private suspend fun slowFailover(oldMbps: Double) = opLock.withLock {
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
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = tr("скорость мала — ищу сервер быстрее…"), link = mainLink))
        val found = withContext(Dispatchers.IO) { searchFor(ordered, want = 4, budgetMs = SLOW_SEARCH_BUDGET_MS, sink = sink) }
        sink.flush()
        val measured = ArrayList<Pair<Found, Double>>()
        for (f in found) {
            if (!wantRunning) return@withLock
            val r = withContext(Dispatchers.IO) { SpeedRunner.run(this@PhoenixVpnService, f.cand.link, maxMs = 4_000, maxBytes = 400_000) }
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

    private val routeFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val ROUTE_QUIET_MS = 120_000L

    /** Проверка серверов, через которые идут цели по варианту А; при двух неудачах подряд цель получает следующий запасной сервер. */
    private suspend fun watchRoutes() {
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
                ProbeShare.measure(cfg, t.checkUrl) { XrayCore.measureDelay(this@PhoenixVpnService, cfg, t.checkUrl) } >= 0 || SpeedRunner.reaches(this@PhoenixVpnService, link, t.checkUrl)
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
    private suspend fun applyPlanIfChanged() = opLock.withLock {
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

    private fun hasSwitchTargets(): Boolean {
        val global = settings.globalPolicy
        return TargetStore(storage.targetsFile).load().any {
            it.kind == TargetKind.APP && it.servers.isNotEmpty() && !it.isDirect && it.resolve(global, finderOk, true) == Policy.SWITCH
        }
    }

    // Вариант Б: основной конфиг до переключения и когда пользователь ушёл из приложения (для возврата).
    private var homeLink: String? = null
    private var homeTitle = ""
    private var switchedPkg = ""
    private var awaySince = 0L

    /** Вариант Б: открыто приложение, которому нужен свой сервер на весь VPN; когда из него ушли — возврат на прежний. */
    private suspend fun watchForeground() {
        // экран — самая дешёвая проверка, идёт первой; targets.tsv (hasSwitchTargets) читаем только если он правда нужен
        val pm = getSystemService(PowerManager::class.java)
        if (!wantRunning || (pm != null && !pm.isInteractive)) return
        if (!ForegroundWatcher.hasAccess(this) || !(switchTargetsCache ?: hasSwitchTargets().also { switchTargetsCache = it })) return
        val pkg = withContext(Dispatchers.IO) { ForegroundWatcher.current(this@PhoenixVpnService) }
        val now = System.currentTimeMillis()

        val target = if (pkg == null) null else PlanBuilder.switchFor(
            pkg, TargetStore(storage.targetsFile).load(), settings.globalPolicy, mainLink, lastSwitchAt, now,
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
                    TargetsWorker.enqueue(this@PhoenixVpnService)
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
        if (awaySince == 0L || now - awaySince < AWAY_BEFORE_RETURN_MS) return
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

    // ---- отключение ----

    private suspend fun disconnect() {
        // сразу останавливаем наблюдение: перебор запасных серверов держит блокировку, и «Отключить» не должно ждать его конца
        wantRunning = false
        settings.vpnWanted = false
        stopMonitors()
        opLock.withLock { doDisconnect() }
    }

    private fun doDisconnect() {
        teardown()
        VpnState.set(VpnStatus())
        storage.log("VPN: отключено")
        stopForegroundCompat()
        stopSelf()
    }

    private fun teardown() {
        statTick()
        statLast = 0L
        try { UsageStats.of(storage.statsFile).save(System.currentTimeMillis()) } catch (_: Throwable) { }
        LocalProxy.port = 0
        LocalProxy.auth = null
        // Сначала ядро, потом интерфейс (порядок важен: иначе ядро может не освободить порты).
        HevTunnel.stop()
        controller?.stop()
        controller = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        activeRoutes = emptyList()
    }

    override fun onRevoke() {
        // Пользователь отключил VPN из системы или включил другой.
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        wantRunning = false
        stopMonitors()
        teardown()
        scope.cancel()
        VpnState.pinned.value = false
        try { EnergyMeter.of(java.io.File(filesDir, "energy.tsv")).save(System.currentTimeMillis()) } catch (_: Throwable) {}
        try { ArmStore.of(java.io.File(storage.base, "bandit.tsv")).save(System.currentTimeMillis()) } catch (_: Throwable) {}
        if (VpnState.status.value.phase != VpnPhase.ERROR) VpnState.set(VpnStatus())
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @Volatile private var lastTone: NotifyTone? = null
    @Volatile private var lastNotifTitle = ""
    @Volatile private var lastNotifText = ""

    private fun showForeground(title: String, text: String, tone: NotifyTone? = null) {
        lastNotifTitle = title
        lastNotifText = text
        val st = VpnState.status.value
        val t = tone ?: NotifyTone.of(st.phase == VpnPhase.CONNECTED, st.phase == VpnPhase.CONNECTING, text.let { if (st.note.isNotEmpty()) st.note else it })
        lastTone = t
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW))

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PhoenixVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // «Сменить сервер» открывает приложение со списком серверов (выбор или «автоматически»)
        val switchIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).putExtra(SwitchRequest.EXTRA_SHOW, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pinned = VpnState.pinned.value
        val smartIntent = PendingIntent.getService(
            this, 5, Intent(this, PhoenixVpnService::class.java).setAction(ACTION_SMART),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unpinIntent = PendingIntent.getService(
            this, 6, Intent(this, PhoenixVpnService::class.java).setAction(ACTION_UNPIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Phoenix VPN · $title")
            .setContentText("${t.mark} $text")
            .setColor(t.argb)
            .setColorized(true)
            .setContentIntent(open)
            .addAction(if (pinned) Notification.Action.Builder(null, tr("Открепить"), unpinIntent).build() else Notification.Action.Builder(null, tr("Сменить сервер"), switchIntent).build())
            .addAction(Notification.Action.Builder(null, tr("Умная смена"), smartIntent).build())
            .addAction(Notification.Action.Builder(null, tr("Отключить"), stop).build())
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // прошивка не дала сделать службу «передним планом» (запрет фонового запуска на Android 12+, ограничения Vivo/Xiaomi):
            // не роняем приложение — пишем причину; сам VPN-туннель от этого не зависит
            try { Storage(this).log("VPN: система не дала показать уведомление службы: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
        }
    }

    companion object {
        const val ACTION_START = "com.phoenix.app.VPN_START"
        const val ACTION_STOP = "com.phoenix.app.VPN_STOP"
        const val ACTION_SWITCH = "com.phoenix.app.VPN_SWITCH"
        const val ACTION_SWITCH_TO = "com.phoenix.app.VPN_SWITCH_TO"
        /** Сколько ждать ответа TCP при поиске: мёртвые серверы отсеиваются быстрее (в V2rayNG — 1 с). */
        const val SEARCH_TCP_TIMEOUT_MS = 1_500
        const val ACTION_SMART = "com.phoenix.app.VPN_SMART"
        const val ACTION_UNPIN = "com.phoenix.app.VPN_UNPIN"
        const val ACTION_VETO = "com.phoenix.app.VPN_VETO"
        const val ACTION_BYPASS = "com.phoenix.app.VPN_BYPASS"
        const val EXTRA_LINK = "link"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "phoenix_vpn"
        private const val NOTIFICATION_ID = 4712
        private const val TUN_ADDRESS = "10.89.64.2"
        private const val TUN_ADDRESS_V6 = "fd89:6400::2"
        private const val MTU = 1500
        private const val HEALTH_PERIOD_MS = 25_000L
        private const val FOREGROUND_PERIOD_MS = 4_000L
        private const val IDLE_PERIOD_MS = 60_000L
        private const val MODE_QUICK_TRIES = 4
        private const val MODE_PREFILTER_PARALLEL = 4
        private const val ROUTE_WATCH_FIRST_MS = 90_000L
        private const val ROUTE_WATCH_PERIOD_MS = 8 * 60_000L
        private const val AWAY_BEFORE_RETURN_MS = 60_000L
        private const val DETECT_GAP_MS = 30_000L
        private const val WHITELIST_CONFIRM_MS = 45_000L
        private const val START_DEBOUNCE_MS = 8_000L
        private const val AUTO_START_GAP_MS = 20_000L
        @Volatile private var lastAutoAt = -AUTO_START_GAP_MS
        private const val SPEED_PERIOD_MS = 60_000L
        private const val ACTIVE_BYTES = 150L * 1024
        private const val FAILOVER_BACKOFF_START_MS = 30_000L
        private const val FAILOVER_BACKOFF_MAX_MS = 5 * 60_000L
        private const val FAILOVER_BUDGET_MS = 120_000L
        private const val SLOW_SEARCH_BUDGET_MS = 45_000L
        private const val DETECT_PERIOD_MS = 5 * 60_000L
        private val SPEED_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=400000",
            "https://proof.ovh.net/files/1Mb.dat",
            "https://cachefly.cachefly.net/1mb.test",
            "https://ash-speed.hetzner.com/100MB.bin",
        )
        private val HEALTH_URLS = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
        )

        /** Вызывать после того, как пользователь дал системное разрешение на VPN (VpnService.prepare). Пустая ссылка — взять лучший конфиг. */
        fun start(context: Context, link: String = "", title: String = "") {
            val intent = Intent(context, PhoenixVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_TITLE, title)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+ запрещает запуск службы из фона (ForegroundServiceStartNotAllowedException) — не роняем приложение
                try { Storage(context).log("VPN: система не разрешила запустить службу: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
                VpnState.set(VpnStatus(VpnPhase.ERROR, "", tr("Система не разрешила запуск VPN из фона — откройте приложение и подключите вручную")))
            }
        }

        /**
         * Автоподключение: при включении настройки «Подключать само», а также всегда, если VPN был явно включён
         * (vpnWanted) — чтобы после загрузки или обновления телефон не остался без защиты только из-за того, что
         * человек не открыл приложение. Без системного разрешения на VPN молча ничего не делает (окно разрешения
         * показывается только в приложении).
         */
        fun autoStart(context: Context, reason: String, onDone: () -> Unit = {}) {
            val settings = AppSettings(context)
            if (!settings.autoConnect && !settings.vpnWanted) { onDone(); return }
            // не IDLE/ERROR — уже подключаемся или подключены; а вот одну неудачную попытку (ERROR) не считаем
            // причиной молчать до перезапуска процесса — иначе один отказ ядра выключает автоподключение насовсем.
            val phase = VpnState.status.value.phase
            if (phase == VpnPhase.CONNECTING || phase == VpnPhase.CONNECTED) { onDone(); return }
            if (!XrayCore.available || VpnService.prepare(context) != null) { onDone(); return }
            val app = context.applicationContext
            // выбор сервера читает все конфиги и строит рейтинг — на главном потоке (открытие приложения, приёмник загрузки) это риск ANR
            val job = Runnable {
                try {
                    if (QuickConnect.pick(app) == null) return@Runnable
                    synchronized(PhoenixVpnService::class.java) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastAutoAt < AUTO_START_GAP_MS) return@Runnable // открытие приложения и загрузка телефона не должны запускать подключение пачкой
                        lastAutoAt = now
                    }
                    Storage(app).log("VPN: автоподключение [$reason]")
                    start(app)
                } catch (_: Throwable) {
                } finally {
                    onDone()
                }
            }
            Thread(job, "phoenix-autostart").start()
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Переключиться на сервер, выбранный в списке. */
        fun switchTo(context: Context, link: String, title: String) {
            context.startService(
                Intent(context, PhoenixVpnService::class.java).setAction(ACTION_SWITCH_TO).putExtra(EXTRA_LINK, link).putExtra(EXTRA_TITLE, title)
            )
        }

        /** Принудительно сменить сервер на другой рабочий (не дожидаясь отказа текущего). Если другого нет — остаётся прежний. */
        fun switchServer(context: Context) {
            context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_SWITCH))
        }

        /** «Умная смена»: принудительная проверка сети и туннеля, затем сервер под то приложение, которое открыто сейчас. */
        fun smartSwitch(context: Context) {
            try { context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_SMART)) } catch (_: Exception) {}
        }

        /** Список обхода изменился: если VPN работает и набор приложений в обход другой — пересоздать туннель с новым набором. */
        fun applyBypass(context: Context) {
            if (VpnState.status.value.phase != VpnPhase.CONNECTED) return // VPN не работает — список подхватится при подключении
            try { context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_BYPASS)) } catch (_: Exception) {}
        }

        /** «Открытый сервис не работает на этом сервере»: пометить и подобрать другой. */
        fun veto(context: Context) {
            try { context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_VETO)) } catch (_: Exception) {}
        }

        /** Снять ручное закрепление сервера: автоперебор снова может его менять. */
        fun unpin(context: Context) {
            try { context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_UNPIN)) } catch (_: Exception) {}
        }
    }
}

/**
 * Копит итоги поиска серверов и сохраняет их разом (чтобы не писать файл на каждый из сотен конфигов):
 * живые — в результаты проверок, ответившие на TCP, но не прошедшие через ядро — как мёртвые, недоступные по TCP — только в опыт сети
 * (в другой сети они могут быть живы, поэтому глобально мёртвыми не помечаются).
 */
class SearchSink(private val storage: Storage, private val context: android.content.Context) {
    private val results = java.util.concurrent.ConcurrentLinkedQueue<Pair<Candidate, Long>>()
    private val total = java.util.concurrent.atomic.AtomicInteger()

    /** Сколько проверок записано всего (в том числе уже сохранённых). */
    val count: Int get() = total.get()

    /** Сколько ещё ждёт сохранения. */
    val pending: Int get() = results.size

    private val alive = java.util.concurrent.atomic.AtomicInteger()
    private val tcpFail = java.util.concurrent.atomic.AtomicInteger()
    private val coreFail = java.util.concurrent.atomic.AtomicInteger()
    private val unsupported = java.util.concurrent.atomic.AtomicInteger()

    fun add(c: Candidate, ms: Long) {
        results += c to ms
        total.incrementAndGet()
        when {
            ms >= 0 -> alive.incrementAndGet()
            ms == ServerSearch.TCP_FAIL -> tcpFail.incrementAndGet()
            ms == CheckServices.UNSUPPORTED -> unsupported.incrementAndGet()
            else -> coreFail.incrementAndGet()
        }
    }

    /** «рабочих 3, TCP не открылся 40, ядро не получило ответ 12, не поддерживается 1» — для журнала. */
    fun statsText(): String =
        "рабочих ${alive.get()}, TCP не открылся ${tcpFail.get()}, ядро не получило ответ ${coreFail.get()}, не поддерживается ${unsupported.get()}"

    fun flush() {
        val items = ArrayList<Pair<Candidate, Long>>()
        while (true) items += results.poll() ?: break
        if (items.isEmpty()) return
        val date = java.time.LocalDate.now().toString()
        try {
            val store = CheckStore(storage.checksFile)
            store.modify { old ->
                val updates = HashMap<String, CheckResult>()
                for ((c, ms) in items) {
                    val key = CheckStore.keyOf(c.link)
                    val prev = old[key]
                    when {
                        ms >= 0 -> if (prev == null || prev.dead || prev.date != date) updates[key] = CheckResult(date, ms, emptyMap(), host = ConfigIdentity.address(c.link))
                        ms == ServerSearch.TCP_FAIL || ms == CheckServices.UNSUPPORTED -> {}
                        else -> updates[key] = CheckResult(date, CheckServices.FAILED, emptyMap(), host = ConfigIdentity.address(c.link))
                    }
                }
                updates
            }
            val ctx = NetInfo.label(context)
            // пачка отказов TCP без единого ответа (или интернета недавно не было) — это скорее сеть, чем серверы: такой «опыт» не записываем
            val netDown = FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis()) ||
                (items.none { it.second >= 0 } && items.count { it.second == ServerSearch.TCP_FAIL } >= 20)
            storage.history().record(items.filter { it.second != CheckServices.UNSUPPORTED && !(netDown && it.second == ServerSearch.TCP_FAIL) }.map { (c, ms) -> HistEvent(c.identity, ctx, ms >= 0, -1.0) })
        } catch (_: Throwable) {
        }
    }
}
