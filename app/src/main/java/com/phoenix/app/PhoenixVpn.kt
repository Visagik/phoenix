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
    /** Уточнение состояния (поиск сервера, нет сети, нет связи через сервер): см. [VpnSub]. */
    val sub: VpnSub = VpnSub.NONE,
)

/** Состояние VPN для интерфейса. Меняется из сервиса. */
object VpnState {
    internal val _status = MutableStateFlow(VpnStatus())
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

    internal fun picksIn(context: Context, storage: Storage, limit: Int): List<Pick> {
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

    internal fun pickIn(context: Context, storage: Storage): Pick? {
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
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            try { Storage(this).log("ERROR: необработанная ошибка в службе: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
        }
    )
    internal val opLock = Mutex()

    internal var tun: ParcelFileDescriptor? = null
    internal var controller: XrayCore.Controller? = null
    internal var mainLink = ""
    internal var mainTitle = ""
    internal var healthPort = 0
    @Volatile internal var healthAuth: SocksAuth? = null
    /** Почему последняя проверка здоровья не прошла (по каждому адресу): идёт в строку HEALTH. */
    @Volatile internal var healthDetail = ""
    /** Идёт принудительная смена сервера (повторные нажатия игнорируются). */
    @Volatile internal var switching = false

    /** IPv6 «в туннель» включён, но сервер IPv6 не пропускает: временно закрываем IPv6 правилом, чтобы приложения сразу шли по IPv4. */
    @Volatile internal var ipv6Auto: Ipv6Mode? = null
    internal var activeRoutes: List<AppRoute> = emptyList()
    internal var monitors: List<Job> = emptyList()
    internal var netCallback: ConnectivityManager.NetworkCallback? = null
    internal var screenReceiver: BroadcastReceiver? = null
    internal var idleReceiver: BroadcastReceiver? = null
    @Volatile internal var wantRunning = false
    @Volatile internal var failures = 0
    /** Когда связь была в порядке в последний раз (подключение или восстановление): начало перерыва не может быть раньше (С1). */
    @Volatile internal var lastConnectedAt = 0L
    /** Прямой замер, начатый заранее (на неудаче перед решающей); относится к эпизоду [directAheadSince] и сети эпохи [directAheadEpoch] (С3). */
    @Volatile internal var directAhead: kotlinx.coroutines.Deferred<Boolean>? = null
    @Volatile internal var directAheadSince = 0L
    @Volatile internal var directAheadEpoch = 0L
    @Volatile internal var directAheadAt = 0L
    /** Эпизод потери связи: начало не сбрасывается защитами «сейчас не виню сервер» (см. OutageEpisode). */
    internal val outage = OutageEpisode()
    @Volatile internal var lastPrewarmSlot = -1
    @Volatile internal var startPending = false
    internal var pendingLink = ""
    internal var lastStartAt = 0L
    /** Режим, данные которого сейчас в работе: при его смене подключение переносится на сервер нового режима. */
    internal var connectedMode: Mode = Mode.WHITELIST
    internal var foreignNote = ""
    internal val slow = SlowMonitor(0.5)
    internal val shape = ShapingDetector()
    internal val appHours by lazy { AppHours(java.io.File(filesDir, "app_hours")) }
    @Volatile internal var lastAppPrepSlot = -1
    internal var lastRx = -1L
    internal var lastTx = -1L
    internal var lastOkRecordAt = 0L
    internal var nextFailoverAt = 0L
    @Volatile internal var modeSwitchedAt = 0L
    /** Сервер, выбранный вручную: автоперебор его не трогает, пока ядро работает (иначе telegram-only уходит из-за общей проверки). */
    @Volatile internal var pinBacking: String? = null
    internal var manualPinLink: String?
        get() = pinBacking
        set(v) { if (v != pinBacking) pinSetAt = if (v != null) System.currentTimeMillis() else 0L; pinBacking = v; VpnState.pinned.value = v != null }
    @Volatile internal var pinSetAt = 0L

    /** Закреплён ли [link] вручную. Закрепление с истёкшим сроком (настройка «Закреплённый сервер») снимается здесь же. */
    internal fun pinned(link: String): Boolean {
        if (manualPinLink == null || manualPinLink != link) return false
        if (PinPolicy.expired(pinSetAt, System.currentTimeMillis(), settings.pinHours)) {
            storage.log("PIN: закрепление [$mainTitle] истекло (${PinPolicy.label(settings.pinHours)}) — дальше сервер выбирается само")
            manualPinLink = null
            return false
        }
        return true
    }
    /** С какого момента у закреплённого вручную сервера не проходит общая проверка (0 — проходит). */
    @Volatile internal var pinFailSince = 0L
    internal var failoverBackoffMs = FAILOVER_BACKOFF_START_MS
    internal val autoSwitchTimes = ArrayList<Long>()
    internal var lastDetectAt = 0L
    internal val lastSwitchAt = HashMap<String, Long>()
    /** Кэш [hasSwitchTargets]: иначе опрос переднего плана читает targets.tsv дважды за круг, в том числе при выключенном экране. Сбрасывается через [PlanBus]. */
    @Volatile internal var switchTargetsCache: Boolean? = null
    internal lateinit var settings: AppSettings

    /** Каждый раз свежий: режим (белые/чёрные списки) мог смениться, пока служба работает. */
    internal val storage: Storage get() = Storage(this)

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
                            failover(forced = true, who = "кнопка «Сменить сервер»")
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
            ACTION_FIX -> {
                if (!wantRunning || tun == null) storage.log("FIX: VPN не запущен — менять нечего")
                else if (switching) storage.log("FIX: смена уже идёт — повторное нажатие проигнорировано")
                else {
                    switching = true
                    scope.launch {
                        try {
                            fixCurrent()
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
                showForeground(title.ifBlank { "Phoenix" }, tr("Подключение…"))
                startPending = true
                pendingLink = link
                scope.launch {
                    try {
                        var foreign: Mode? = null
                        if (link.isBlank()) {
                            QuickConnect.pickEx(this@PhoenixVpnService)?.let { p -> link = p.link; title = p.title; foreign = p.foreign }
                        }
                        if (link.isBlank()) {
                            fail(tr("Нет конфига для подключения: выберите его в списке"), title)
                        } else {
                            pendingLink = link
                            connect(link, title, foreign)
                            if (explicit && wantRunning && mainLink == link) { manualPinLink = link; setConnected(foreignNote) }
                        }
                    } catch (e: Throwable) {
                        storage.log("VPN: ошибка запуска: ${e.javaClass.simpleName}: ${e.message}")
                        fail(tr("Ошибка запуска: {}", e.message ?: e.javaClass.simpleName), title)
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

    /** Набор приложений, не идущих через VPN в текущем туннеле (в режиме «только выбранные» — набор «+пакет» разрешённых). */
    @Volatile internal var appliedBypass: Set<String> = emptySet()
    /** DNS, с которым создан текущий туннель: если в настройках стал другой, туннель пересоздаётся. */
    @Volatile internal var appliedDns: String = ""

    /**
     * Правила приложений для туннеля: (true, разрешённые) в режиме «только выбранные», иначе (false, обход).
     * Если выбранных установленных приложений нет, режим не применяется: пустой список разрешённых Android понял бы как «все».
     */
    internal fun appRules(installed: Collection<String>): Pair<Boolean, Set<String>> {
        if (settings.appsMode == AppsMode.ONLY_SELECTED) {
            val targetPkgs = try {
                TargetStore(storage.targetsFile).load().filter { it.kind == TargetKind.APP }.map { it.value }.toSet()
            } catch (_: Throwable) { emptySet() }
            val allowed = AppsMode.allowed(AppsMode.decode(settings.onlyApps), targetPkgs, installed, packageName)
            if (allowed.isNotEmpty()) return true to allowed
            storage.log("VPN: режим «только выбранные» без установленных приложений — работаю в режиме «все, кроме списка»")
        }
        return false to BypassStore(storage.base, storage.mode, NetInfo.label(this)).effective(installed)
    }

    internal suspend fun applyBypassNow() {
        val want = appRules(installedPackagesForBypass()).let { (only, set) -> if (only) set.map { "+$it" }.toSet() else set }
        val dnsNow = DnsProvider.fromId(settings.dnsProvider).name
        if (want == appliedBypass && dnsNow == appliedDns) {
            storage.log("BYPASS: список обхода не изменился (${want.size}) — туннель не трогаю")
            return
        }
        if (dnsNow != appliedDns) storage.log("DNS: сменился на ${DnsProvider.fromId(dnsNow).title} — пересоздаю туннель, сервер прежний [$mainTitle]")
        val added = (want - appliedBypass).size
        val removed = (appliedBypass - want).size
        storage.log("BYPASS: список обхода изменился (добавлено $added, убрано $removed) — пересоздаю туннель с новым списком, сервер прежний [$mainTitle]")
        val link = mainLink
        val title = mainTitle
        if (!wantRunning || link.isBlank()) return
        connect(link, title)
    }

    internal suspend fun connect(link: String, title: String, foreign: Mode? = null) = opLock.withLock {
        startPending = false
        wantRunning = true
        failures = 0
        outage.end()
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // отмена (ручное «Отключить» во время подключения) — не ошибка: без этого на главной появлялась «Ошибка VPN», а с «Не пускать без VPN» туннель мог остаться поднятым
            storage.log("VPN: подключение к [$title] отменено")
            throw e
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
                VpnState.set(VpnStatus(VpnPhase.CONNECTED, title, note = tr("нет защищённого соединения — интернет заблокирован ({})", message), link = link, sub = VpnSub.BROKEN))
                showForeground(title.ifBlank { "Phoenix" }, tr("Интернет заблокирован: ищу сервер"))
                startMonitors()
            } else {
                teardown()
                wantRunning = false
                fail(message, title)
            }
        }
    }

    /** Одна строка с условиями, в которых работает туннель: без неё по журналу не понять, почему на разных телефонах по-разному. */
    internal fun logEnvironment() {
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

    internal fun fail(message: String, title: String) {
        startPending = false
        settings.vpnWanted = false
        VpnState.set(VpnStatus(VpnPhase.ERROR, title, message))
        stopForegroundCompat()
        stopSelf()
    }

    internal fun setConnected(note: String = "") {
        lastConnectedAt = System.currentTimeMillis()
        // закреплённый вручную сервер помечаем, чтобы было видно, почему автоперебор молчит
        val shown = if (pinned(mainLink)) "📌 закреплён" + (if (note.isBlank()) "" else " · $note") else note
        VpnState.set(VpnStatus(VpnPhase.CONNECTED, mainTitle, note = shown, link = mainLink))
        showForeground(mainTitle.ifBlank { "Phoenix" }, shown.ifBlank { tr("Подключено") })
    }

    internal fun uidOf(pkg: String): String? = try {
        // getPackageUid — с API 24; раньше тот же uid даёт getApplicationInfo
        (if (Build.VERSION.SDK_INT >= 24) packageManager.getPackageUid(pkg, 0) else packageManager.getApplicationInfo(pkg, 0).uid).toString()
    } catch (_: Exception) {
        null
    }

    internal fun freePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (_: Exception) {
        0
    }

    /** Узнаёт ли ядро приложение по соединению (проверяется при первом запуске ядра с маршрутами по приложениям). */
    @Volatile internal var finderOk = true

    internal fun currentRoutes(link: String): List<AppRoute> {
        TargetSyncIO.merge(this) // список целей общий для режимов (после смены режима не терять цели)
        // цели «мимо VPN», которым напрямую в нынешней сети не открывается, идут общим путём (см. DirectLearn.blockedTargets)
        val directBlocked = try {
            val net = NetInfo.label(this)
            if (net.isEmpty()) emptySet() else DirectLearn.blockedTargets(DirectLearn.load(BypassStore(storage.base, storage.mode, net).learnFile), net)
        } catch (_: Throwable) { emptySet() }
        return PlanBuilder.routes(
            link, TargetStore(storage.targetsFile).load(), settings.globalPolicy,
            finderOk = finderOk, usageOk = ForegroundWatcher.hasAccess(this), uidOf = ::uidOf, directBlocked = directBlocked,
        )
    }

    /** Запускает ядро с [link] как основным; интерфейс TUN остаётся тем же (как делает v2rayNG при перезагрузке). */
    internal fun startCore(link: String, forceBuiltin: Boolean = false) {
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
            dns = DnsProvider.fromId(settings.dnsProvider).plain,
            secureServers = DnsProvider.fromId(settings.dnsProvider).secure,
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
        storage.log("CORE: обработчик — ${c.handlerKind}")
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
    @Volatile internal var hevActive = false
    @Volatile internal var hevCrashed = false

    /** Перезапуск ядра с другим основным конфигом. Вызывать под opLock. */
    internal fun reloadCore(link: String) {
        coreGen++
        try {
            controller?.stop()
            controller = null
            startCore(link)
            mainLink = link
            mainChosenAt = System.currentTimeMillis()
            disturbedAt = System.currentTimeMillis()
        } finally {
            coreGen++
        }
    }

    /** Меняется при каждом перезапуске ядра: результат проверки связи, начатой при другом значении, устарел. */
    @Volatile internal var coreGen = 0

    internal fun findUid(protocol: String, srcIp: String, srcPort: Long, dstIp: String, dstPort: Long): Long {
        val cm = getSystemService(ConnectivityManager::class.java)
        val proto = if (protocol.equals("udp", true)) 17 else 6
        if (Build.VERSION.SDK_INT < 29) return -1L // getConnectionOwnerUid есть с Android 10: приложение неизвестно
        return try {
            cm.getConnectionOwnerUid(proto, InetSocketAddress(srcIp, srcPort.toInt()), InetSocketAddress(dstIp, dstPort.toInt())).toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    internal fun installedPackagesForBypass(): List<String> = try {
        packageManager.getInstalledApplications(0).map { it.packageName }
    } catch (_: Throwable) {
        emptyList()
    }

    internal fun buildInterface(): ParcelFileDescriptor {
        // непроверенный вовремя DNS не оставляем: если проба просрочена (приложение закрыли, пока шёл отсчёт), возвращаем прежний
        if (settings.settleDnsTrial(System.currentTimeMillis())) storage.log("DNS: проба не подтверждена — возвращён ${DnsProvider.fromId(settings.dnsProvider).title}")
        val dns = DnsProvider.fromId(settings.dnsProvider)
        appliedDns = dns.name
        val builder = Builder()
            .setSession("Phoenix")
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
        dns.tun.forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        // IPv6: если его не заводить в туннель, трафик по IPv6 пойдёт мимо VPN (утечка).
        if (settings.ipv6Mode != Ipv6Mode.OFF) {
            builder.addAddress(TUN_ADDRESS_V6, 126).addRoute("::", 0)
        }

        val installed = try {
            packageManager.getInstalledApplications(0).map { it.packageName }
        } catch (_: Throwable) {
            emptyList()
        }
        val (onlySelected, appSet) = appRules(installed)
        if (onlySelected) {
            // «только выбранные»: через туннель идут перечисленные приложения; Phoenix в список не входит, поэтому остаётся мимо туннеля
            appliedBypass = appSet.map { "+$it" }.toSet()
            var added = 0
            for (pkg in appSet) {
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            // ни одно не добавилось: Phoenix остался бы внутри собственного туннеля, поэтому выводим его из туннеля явно
            if (added == 0) try { builder.addDisallowedApplication(packageName) } catch (_: PackageManager.NameNotFoundException) { }
            storage.log("VPN: через туннель только выбранные приложения ($added)")
        } else {
            // Само приложение всегда мимо VPN: иначе соединения ядра к серверу пошли бы в собственный туннель.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
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
        }

        return builder.establish() ?: throw IllegalStateException("Система не выдала VPN-интерфейс (нет разрешения на VPN?)")
    }

    // ---- наблюдение за туннелем ----

    internal fun startMonitors() {
        stopMonitors()
        lastNetLabel = NetInfo.place(this)
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
                    delay(HealthPace.nextDelay(screenOn, if (outage.active) maxOf(failures, 1) else failures, calm, hot = PowerInfo.hot(this@PhoenixVpnService), lowBattery = PowerInfo.lowBattery(this@PhoenixVpnService), mobile = NetInfo.kind(this@PhoenixVpnService) == NetInfo.Kind.MOBILE))
                    if (storage.mode != connectedMode) PlanBus.notifyChanged() // режим сменили: перенесём подключение
                    if (screenOn) try { ActivityStore.get(this@PhoenixVpnService).recordActive() } catch (_: Throwable) {}
                    if (screenOn) withContext(Dispatchers.IO) { recordAppUse() }
                    prepareApps()
                    if (!screenOn && failures == 0 && !outage.active) {
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
                    calm = if (failures == 0 && !outage.active && disturbedAt < System.currentTimeMillis() - 60_000L) calm + 1 else 0
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
                val tone = NotifyTone.of(st.phase == VpnPhase.CONNECTED, st.phase == VpnPhase.CONNECTING, st.note, st.sub)
                val text = st.note.ifBlank { lastNotifText }
                if (lastNotifTitle.isNotEmpty() && NotifyTone.needsRefresh(tone, lastTone, text, lastNotifText)) {
                    showForeground(lastNotifTitle, text, tone)
                    delay(300) // частые смены заметки склеиваются: система не любит сотни обновлений в секунду
                }
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
            var first = true
            while (true) {
                try {
                    // сразу после подключения тип блокировок в этой сети ещё не известен (журнал 22.11: первое определение
                    // через 62 с, всё это время режим «по умолчанию» — белый в чёрной сети): определяем сразу, а не через минуту
                    if (first) {
                        first = false
                        if (settings.autoMode && ModeGate.needsDetect(true, ModeGate.knownNet, NetInfo.place(this@PhoenixVpnService), false)) {
                            detectMode(force = true, decisive = true)
                            continue
                        }
                    }
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

    internal fun stopMonitors() {
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

    internal fun registerNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                // первый вызов — обычно уже существующая сеть; но если служба стартовала без сети, это как раз её появление — тогда не пропускаем
                if (first) { first = false; if (NetInfo.place(this@PhoenixVpnService) == lastNetLabel) return }
                scope.launch {
                    delay(1_000) // дать сети устояться
                    onNetworkChanged()
                }
            }

            // ушла Wi-Fi (или мобильная): телефон перешёл на оставшуюся сеть, onAvailable для неё не вызывается — иначе заметили бы только через 5 минут
            // система предупреждает, что сеть скоро пропадёт: не считаем сбои маршрутов и не начинаем тяжёлое
            override fun onLosing(network: Network, maxMsToLive: Int) {
                disturbedAt = System.currentTimeMillis()
                NetState.shake(disturbedAt)
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

    internal fun registerScreen() {
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
    @Volatile internal var lastNetLabel = ""

    /** Когда в последний раз менялись сеть, режим или ядро: сразу после этого проверки целей дают ложные «не отвечает». */
    @Volatile internal var disturbedAt = 0L

    /** Когда сеть менялась или система предупредила о её потере (см. [HealthGate]); перезапуск ядра сюда не входит. */
    /** Когда выбран текущий основной сервер. */
    @Volatile internal var mainChosenAt = 0L

    /** Последний запуск определения режима по смене сети: при двух сменах подряд (wifi → none → mobile) второй отменяет первый, а не бежит параллельно с ним. */
    @Volatile internal var detectJob: Job? = null

    /** Режим, взятый по подсказке сети до подтверждения определением (см. ModeHint). */
    internal val provisional = ProvisionalMode()

    /**
     * Смена сети: определение типа блокировок идёт долго, а серверы нужны сразу. Если в этой сети режим уже подтверждался,
     * берём его сразу и ищем серверы в нём; определение подтвердит или поправит. Взятое по подсказке в прежней сети и не
     * подтверждённое — возвращаем на место.
     */
    /** Проверка серверов шла в режиме, взятом по догадке, а она не подтвердилась: её итоги (карантин) не должны вредить серверам. */
    internal fun annulProvisional(since: Long, why: String) {
        val n = Quarantine.annulSince(since)
        failures = 0
        if (n > 0) storage.log("MODE: $why — итоги проверки серверов по подсказке аннулированы (снят карантин у $n)")
    }

    @Volatile internal var hintDoubtAt = 0L

    /** Лучшие серверы режима по подсказке не работают: довод против догадки, тип блокировок перепроверяем сразу, а не по расписанию. */
    internal fun doubtHint(): Boolean {
        if (!provisional.active || hintDoubtAt >= provisional.since) return false
        hintDoubtAt = System.currentTimeMillis()
        storage.log("MODE: серверы режима «${storage.mode.title}» по подсказке не работают — довод против догадки, перепроверяю тип блокировок сразу")
        detectJob?.cancel()
        detectJob = scope.launch { detectMode(force = true, decisive = true) }
        return true
    }

    internal fun applyModeHint(label: String) {
        if (!settings.autoMode) return
        val provSince = provisional.since
        provisional.revertOnNetChange(label)?.let { back ->
            annulProvisional(provSince, "сеть сменилась")
            if (storage.mode != back) {
                Mode.write(applicationContext.filesDir, back)
                storage.log("MODE: сеть сменилась, подсказка не подтвердилась — режим возвращён на «${back.title}»")
                PlanBus.notifyChanged()
            }
        }
        if (NetInfo.kind(this) == NetInfo.Kind.NONE) return
        val cur = storage.mode
        val hints = ModeHint.load(storage.modeHintFile)
        if (ModeHint.usual(hints, label) != null) ModeGate.knownNet = label // сеть знакомая: режим известен по памяти
        val guess = ModeHint.guess(hints, label, cur) ?: return
        provisional.start(cur, label)
        Mode.write(applicationContext.filesDir, guess)
        storage.log("MODE: в сети $label раньше было «${guess.title}» — беру сразу, пока определение не подтвердит (не подтвердится — вернусь)")
        PlanBus.notifyChanged()
    }

    internal suspend fun onNetworkChanged() {
        val label = NetInfo.place(this) // для Wi-Fi с отпечатком: переход между двумя Wi-Fi тоже смена сети
        val changed = label != lastNetLabel
        if (changed) storage.log("NET: сеть сменилась: ${lastNetLabel.ifEmpty { "?" }} → $label")
        if (changed) NetEpoch.bump() // факты о прежней сети («нет интернета», тип блокировок) больше не действуют
        // перерыв прежней сети закрываем с причиной «сеть», а не переносим в новую: иначе он приплюсуется к простою уже другой сети (С1)
        if (changed && (outageAt > 0L || outage.active)) { closeOutage(DowntimeStats.NET); outage.end() }
        lastNetLabel = label
        // адреса серверов, найденные в прежней сети, в новой могут быть другими
        if (changed) ServerHosts.clear()
        if (changed && NetInfo.kind(this) != NetInfo.Kind.NONE) {
            // сеть появилась: если плановый сбор был пропущен из-за её отсутствия, запускаем сразу, а не ждём будильника
            try { Scheduler.catchUpIfNeeded(applicationContext, "network") } catch (_: Throwable) {}
        }
        if (changed) {
            disturbedAt = System.currentTimeMillis()
            NetState.shake(disturbedAt)
            routeFails.clear()
            // ручной выбор режима был сделан в прежней сети; вернувшись в сеть с тем же названием, он не должен заглушать определение (в логе — 13 минут без связи)
            if (settings.manualModeAt > 0L) { settings.manualModeAt = 0L; storage.log("MODE: сеть сменилась — ручной выбор режима больше не удерживается, определение снова решает само") }
            applyModeHint(label)
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
            try {
                var changes = withContext(Dispatchers.IO) { DirectProbe.run(this@PhoenixVpnService, storage, onlyDue = true) }
                // сразу после смены сети (журнал: белые списки, мобильная) проверка шла, пока сеть устаивалась, и ничего не открылось:
                // повторяем через 20 секунд, а не ждём следующей перепроверки — иначе DeepSeek, Qwen и Steam часами шли напрямую
                var retries = 0
                while (changes == DirectProbe.NO_NETWORK && retries < 3 && wantRunning) {
                    retries++
                    delay(20_000)
                    changes = withContext(Dispatchers.IO) { DirectProbe.run(this@PhoenixVpnService, storage, onlyDue = true) }
                }
                // в этой сети часть приложений теперь идёт через VPN (напрямую закрыты): серверы для них подбираем сейчас, а не при следующей перепроверке
                if (changes > 0) { storage.log("TARGETS: список обхода в новой сети изменился — запускаю подбор серверов для целей через ${TARGETS_AFTER_REBUILD_SEC} с (после пересборки туннеля)"); TargetsWorker.enqueue(applicationContext, TARGETS_AFTER_REBUILD_SEC) }
            } catch (e: Exception) { storage.log("BYPASS: ошибка проверки в новой сети: ${e.message}") }
        }
        // старые сокеты ядра остались на ушедшей сети и висят 10–15 секунд: если связи нет, пересоздаём их тем же сервером, не меняя сервер
        val genBeforeCheck = coreGen // проверка идёт до 15 с и замок могут занять смена сервера или режима: после неё ядро уже не то (С2)
        if (changed && homeLink == null && controller?.isRunning == true && !healthy()) {
            val ok = opLock.withLock {
                if (!wantRunning) false else if (!NetReload.needed(genBeforeCheck, coreGen, healthy = false)) {
                    storage.log("NET: сервер уже сменили, пока проверял — сокеты не трогаю")
                    false
                } else try {
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
    internal suspend fun detectMode(force: Boolean = false, decisive: Boolean = false) {
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
        // определение дало ясный тип: подсказка либо подтвердилась, либо поправлена самим определением — взятое по ней больше не «временное»
        if (ModeDecider.modeOf(outcome.type) != null && provisional.active) {
            val provSince = provisional.since
            provisional.confirm()
            if (outcome.switchedTo != null) annulProvisional(provSince, "блокировка оказалась другой")
            storage.log("MODE: определение ${if (outcome.switchedTo != null) "поправило подсказку" else "подтвердило подсказку"} — режим «${storage.mode.title}»")
        }
        if (outcome.switchedTo != null) PlanBus.notifyChanged() // режим сменился — данные теперь из другой папки
    }

    /** Туннель после смены сервера: связь есть два раза подряд (один удачный ответ может быть случайным). */
    internal suspend fun tunnelWorks(): Boolean {
        delay(900)
        if (!healthy()) return false
        delay(800)
        return healthy()
    }

    @Volatile internal var statLast = 0L

    /** Учитывает время работы VPN на текущем сервере (вызывать перед сменой названия сервера, при проверках и при отключении). */
    internal fun statTick(now: Long = System.currentTimeMillis()) {
        val last = statLast
        if (last <= 0L) return
        val delta = now - last
        if (delta < 1000L) return
        try { UsageStats.of(storage.statsFile).addConnected(mainTitle, delta / 1000L, java.time.LocalDate.now().toString(), now) } catch (_: Throwable) { }
        statLast = last + delta / 1000L * 1000L
    }

    internal fun stat(key: String) {
        try { UsageStats.of(storage.statsFile).add(key, 1, java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) { }
    }

    /** Расход на замер скорости кандидата по его настоящему объёму. */
    internal fun energySpeedBytes(bytes: Long) {
        try { EnergyMeter.of(java.io.File(filesDir, "energy.tsv")).addSpeed(bytes, java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    internal fun energy(kind: String, count: Int = 1) {
        try { EnergyMeter.of(java.io.File(filesDir, "energy.tsv")).add(kind, count, java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    internal val flight = SingleFlight()

    @Volatile internal var lastBusySkipLogAt = 0L

    /** Серверы, подходящие открытому приложению/сайту (для проверки запасного): null — контекста нет, ограничений нет. */
    @Volatile internal var appFit: Set<String>? = null

    /** Контекст, под который сейчас идёт смена сервера по DIAG (не меняется, если пользователь успел открыть другое приложение). */
    @Volatile internal var diagCtx: AppContext.Now? = null

    /** Опыт приложения: конфиг [link] в текущей сети сработал или нет (и с какой скоростью). Ничего не проверяет сам. */
    internal val flap = FlapGuard()

    @Volatile internal var diagAt = 0L
    @Volatile internal var diagRunning = false
    internal val autoVeto = AutoVetoGate()
    @Volatile internal var diagLastKey = ""

    /** Когда началась потеря связи из-за смены режима/сервера (0 — перерыва нет); для строки DOWNTIME в журнале. */
    @Volatile internal var outageAt = 0L

    internal fun openOutage() {
        val now = System.currentTimeMillis()
        // прежний перерыв так и не закрылся (сервер не нашёлся или сети не было): считаем заново, иначе он приплюсуется к следующему (журнал 07.11: «перерыв 2331 с»)
        if (outageAt == 0L || now - outageAt > DowntimeStats.MAX_SEC * 1000L) outageAt = OutageStart.pick(now, outage.since, lastConnectedAt) // от первой неудачи проверки, а не от начала смены
    }

    internal fun closeOutage(kind: String) {
        val start = outageAt
        outageAt = 0L
        if (start <= 0L) return
        val sec = ((System.currentTimeMillis() - start) / 1000L).toInt()
        try {
            val day = java.time.LocalDate.now().toString()
            val st = DowntimeStats.shared(storage.downtimeFile, storage.legacyDowntimeFiles)
            if (st.add(kind, sec, day)) storage.log("DOWNTIME: связи не было $sec с (${DowntimeStats.kindTitle(kind)}). За сегодня: ${st.summary(day)}")
            else storage.log("DOWNTIME: перерыв $sec с — дольше ${DowntimeStats.MAX_SEC / 60} мин, в учёт не беру (похоже, интернета не было совсем)")
        } catch (_: Throwable) {
        }
    }

    /**
     * Одна кнопка «Не работает» вместо двух («Умная смена» и «Не открывается»). Сначала разбирается, где поломка: нет сети, нет интернета
     * или туннель не отвечает — это дело «умной смены» (сервис не помечается закрытым, иначе сервер зря выпал бы на 3 дня).
     * Если туннель жив и понятно, какой сервис открыт, значит не работает именно он — помечаем его на этом сервере и берём другой.
     * Если сервис определить не удалось — «умная смена» подбирает сервер под открытое приложение.
     */
    /**
     * (нет сети, сеть без интернета, туннель работает). Сначала быстрая проверка туннеля (до 5–10 с): ответил — значит, сеть есть, и
     * 14-секундный опрос десятка адресов не нужен. Раньше кнопки «Не работает» и «Умная смена» ждали этот опрос всегда.
     */
    internal suspend fun quickNetState(): Triple<Boolean, Boolean, Boolean> {
        val none = NetInfo.kind(this) == NetInfo.Kind.NONE
        if (none) return Triple(true, false, false)
        if (withContext(Dispatchers.IO) { try { healthy() } catch (_: Throwable) { false } }) return Triple(false, false, true)
        val offline = withContext(Dispatchers.IO) { BlockDetector.classify(ModeDetector.measureConfirmed(this@PhoenixVpnService)) == BlockType.OFFLINE }
        return Triple(false, offline, false)
    }

    @Volatile internal var smartVerdictText = ""

    // ---- подготовка серверов к приложениям по часам их открытия ----

    // ---- низкая скорость ----

    internal val snapshotUid = android.os.Process.myUid()
    internal var speedProbeFailedLogged = false
    @Volatile internal var speedErr = ""

    internal val routeFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val ROUTE_QUIET_MS = 120_000L

    // Вариант Б: основной конфиг до переключения и когда пользователь ушёл из приложения (для возврата).
    internal var homeLink: String? = null
    internal var homeTitle = ""
    internal var switchedPkg = ""
    internal var awaySince = 0L

    // ---- отключение ----

    internal suspend fun disconnect() {
        // сразу останавливаем наблюдение: перебор запасных серверов держит блокировку, и «Отключить» не должно ждать его конца
        wantRunning = false
        settings.vpnWanted = false
        stopMonitors()
        opLock.withLock { doDisconnect() }
    }

    internal fun doDisconnect() {
        teardown()
        VpnState.set(VpnStatus())
        storage.log("VPN: отключено")
        stopForegroundCompat()
        stopSelf()
    }

    internal fun teardown() {
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

    internal fun stopForegroundCompat() {
        // stopForeground(Int) — с API 24; на Android 6 NoSuchMethodError (это Error, catch (Exception) его не ловит)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    @Volatile internal var lastTone: NotifyTone? = null
    @Volatile internal var lastNotifTitle = ""
    @Volatile internal var lastNotifText = ""

    internal fun showForeground(title: String, text: String, tone: NotifyTone? = null) {
        lastNotifTitle = title
        lastNotifText = text
        val st = VpnState.status.value
        val t = tone ?: NotifyTone.of(st.phase == VpnPhase.CONNECTED, st.phase == VpnPhase.CONNECTING, text.let { if (st.note.isNotEmpty()) st.note else it }, st.sub)
        lastTone = t
        NotifChannels.ensure(this, CHANNEL_ID, tr("Phoenix: VPN и статус"), NotificationManager.IMPORTANCE_LOW)

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
            this, 8, Intent(this, PhoenixVpnService::class.java).setAction(ACTION_FIX),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unpinIntent = PendingIntent.getService(
            this, 6, Intent(this, PhoenixVpnService::class.java).setAction(ACTION_UNPIN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(when (t) { NotifyTone.GREEN -> R.drawable.ic_stat_ok; NotifyTone.YELLOW -> R.drawable.ic_stat_search; NotifyTone.RED -> R.drawable.ic_stat_error })
            .setContentTitle("Phoenix VPN · $title")
            .setContentText(text)
            .setColor(t.argb)
            .setColorized(true)
            .setContentIntent(open)
            .addAction(if (pinned) androidx.core.app.NotificationCompat.Action.Builder(0, tr("Открепить"), unpinIntent).build() else androidx.core.app.NotificationCompat.Action.Builder(0, tr("Сменить сервер"), switchIntent).build())
            .addAction(androidx.core.app.NotificationCompat.Action.Builder(0, tr("Не работает"), smartIntent).build())
            .addAction(androidx.core.app.NotificationCompat.Action.Builder(0, tr("Отключить"), stop).build())
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
        const val ACTION_FIX = "com.phoenix.app.VPN_FIX"
        const val ACTION_UNPIN = "com.phoenix.app.VPN_UNPIN"
        const val ACTION_VETO = "com.phoenix.app.VPN_VETO"
        const val ACTION_BYPASS = "com.phoenix.app.VPN_BYPASS"
        const val EXTRA_LINK = "link"
        const val EXTRA_TITLE = "title"
        internal const val CHANNEL_ID = "phoenix_vpn"
        internal const val NOTIFICATION_ID = 4712
        internal const val TUN_ADDRESS = "10.89.64.2"
        internal const val TUN_ADDRESS_V6 = "fd89:6400::2"
        internal const val MTU = 1500
        internal const val HEALTH_PERIOD_MS = 25_000L
        internal const val FOREGROUND_PERIOD_MS = 4_000L
        internal const val IDLE_PERIOD_MS = 60_000L
        internal const val MODE_QUICK_TRIES = 4
        internal const val MODE_PREFILTER_PARALLEL = 4
        internal const val MODE_PREFILTER_GRACE_MS = 1_500L
        internal const val MODE_PREFILTER_MAX_MS = 15_000L
        internal const val ROUTE_WATCH_FIRST_MS = 90_000L
        internal const val ROUTE_WATCH_PERIOD_MS = 8 * 60_000L
        internal const val AWAY_BEFORE_RETURN_MS = 60_000L
        internal const val DETECT_GAP_MS = 30_000L
        internal const val WHITELIST_CONFIRM_MS = 45_000L
        internal const val START_DEBOUNCE_MS = 8_000L
        internal const val AUTO_START_GAP_MS = 20_000L
        @Volatile internal var lastAutoAt = -AUTO_START_GAP_MS
        internal const val SPEED_PERIOD_MS = 60_000L
        internal const val ACTIVE_BYTES = 150L * 1024
        internal const val FAILOVER_BACKOFF_START_MS = 30_000L
        internal const val TARGETS_AFTER_REBUILD_SEC = 45L
        internal const val FAILOVER_BACKOFF_MAX_MS = 5 * 60_000L
        internal const val FAILOVER_BUDGET_MS = 120_000L
        internal const val SLOW_SEARCH_BUDGET_MS = 45_000L
        internal const val DETECT_PERIOD_MS = 5 * 60_000L
        internal val SPEED_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=400000",
            "https://proof.ovh.net/files/1Mb.dat",
            "https://cachefly.cachefly.net/1mb.test",
            "https://ash-speed.hetzner.com/100MB.bin",
        )
        internal val HEALTH_URLS = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
            // тот же адрес, что у пробы при поиске сервера (TLS, порт 443): бесплатные серверы часто открывают наружу только 443,
            // и поиск говорил «жив», а проверка по порту 80 писала «пустой ответ» (журналы 20–21.11: 13 и 7 случаев)
            "https://www.gstatic.com/generate_204",
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

        /** DNS туннеля сменили: если VPN работает, туннель пересоздаётся с новым (тот же путь, что и у списка обхода). */
        fun reapplyDns(context: Context) = applyBypass(context)

        /** Список обхода изменился: если VPN работает и набор приложений в обход другой — пересоздать туннель с новым набором. */
        fun applyBypass(context: Context) {
            if (VpnState.status.value.phase != VpnPhase.CONNECTED) return // VPN не работает — список подхватится при подключении
            try { context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_BYPASS)) } catch (_: Exception) {}
        }

        /** «Открытый сервис не работает на этом сервере»: пометить и подобрать другой. */
        /** «Не работает»: гибрид «умной смены» и «не открывается» (см. fixCurrent). */
        fun fix(context: Context) {
            try { context.startService(Intent(context, PhoenixVpnService::class.java).setAction(ACTION_FIX)) } catch (_: Exception) {}
        }

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
    internal val results = java.util.concurrent.ConcurrentLinkedQueue<Pair<Candidate, Long>>()
    internal val total = java.util.concurrent.atomic.AtomicInteger()

    /** Сколько проверок записано всего (в том числе уже сохранённых). */
    val count: Int get() = total.get()

    /** Сколько ещё ждёт сохранения. */
    val pending: Int get() = results.size

    internal val alive = java.util.concurrent.atomic.AtomicInteger()
    internal val tcpFail = java.util.concurrent.atomic.AtomicInteger()
    internal val coreFail = java.util.concurrent.atomic.AtomicInteger()
    internal val unsupported = java.util.concurrent.atomic.AtomicInteger()
    internal val hostSkipped = java.util.concurrent.atomic.AtomicInteger()

    /** Вызывается после записи пачки результатов (перепроверка запоминает по ней, какие конфиги круга уже проверены). */
    @Volatile var onFlushed: ((List<Pair<Candidate, Long>>) -> Unit)? = null

    fun add(c: Candidate, ms: Long) {
        results += c to ms
        total.incrementAndGet()
        when {
            ms >= 0 -> alive.incrementAndGet()
            ms == ServerSearch.TCP_FAIL -> tcpFail.incrementAndGet()
            ms == CheckServices.UNSUPPORTED -> unsupported.incrementAndGet()
            ms == ServerSearch.HOST_SKIPPED -> hostSkipped.incrementAndGet()
            else -> coreFail.incrementAndGet()
        }
    }

    /** «рабочих 3, TCP не открылся 40, ядро не получило ответ 12, не поддерживается 1» — для журнала. */
    fun statsText(): String =
        "рабочих ${alive.get()}, TCP не открылся ${tcpFail.get()}, ядро не получило ответ ${coreFail.get()}, не поддерживается ${unsupported.get()}" + (if (hostSkipped.get() > 0) ", пропущено по адресу ${hostSkipped.get()}" else "")

    fun flush() {
        val items = ArrayList<Pair<Candidate, Long>>()
        while (true) items += results.poll() ?: break
        if (items.isEmpty()) return
        val date = java.time.LocalDate.now().toString()
        // пачка отказов TCP без единого ответа (или интернета недавно не было) — это скорее сеть, чем серверы: ни «мёртв» на 2 дня, ни опыт не записываем
        val netDown = FailoverGate.offlineRecently(ModeDetector.offlineAt, System.currentTimeMillis()) ||
            (items.none { it.second >= 0 } && items.count { it.second == ServerSearch.TCP_FAIL } >= 20)
        try {
            val store = CheckStore(storage.checksFile)
            store.modify { old ->
                val updates = HashMap<String, CheckResult>()
                for ((c, ms) in items) {
                    val key = CheckStore.keyOf(c.link)
                    val prev = old[key]
                    when {
                        ms >= 0 -> CheckUpdate.forLive(prev, ms, date, ConfigIdentity.address(c.link))?.let { updates[key] = it }
                        ms == ServerSearch.TCP_FAIL || ms == CheckServices.UNSUPPORTED || ms == ServerSearch.HOST_SKIPPED -> {}
                        else -> if (!netDown) updates[key] = CheckResult(date, CheckServices.FAILED, emptyMap(), host = ConfigIdentity.address(c.link))
                    }
                }
                updates
            }
            val ctx = NetInfo.label(context)
            try { onFlushed?.invoke(items) } catch (_: Throwable) { }
            storage.history().record(items.filter { it.second != CheckServices.UNSUPPORTED && it.second != ServerSearch.HOST_SKIPPED && !(netDown && it.second == ServerSearch.TCP_FAIL) }.map { (c, ms) -> HistEvent(c.identity, ctx, ms >= 0, -1.0) })
        } catch (_: Throwable) {
        }
    }
}
