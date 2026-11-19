package com.phoenix.desktop

import com.phoenix.app.Baseline
import com.phoenix.app.CheckResult
import com.phoenix.app.CheckService
import com.phoenix.app.CheckServices
import com.phoenix.app.CheckStore
import com.phoenix.app.Collector
import com.phoenix.app.ConfigIdentity
import com.phoenix.app.ConfigRepository
import com.phoenix.app.DefaultSources
import com.phoenix.app.ExitStore
import com.phoenix.app.GeoIp
import com.phoenix.app.LocalSocks
import com.phoenix.app.NetHooks
import com.phoenix.app.Ranking
import com.phoenix.app.ServerHosts
import com.phoenix.app.ServiceChecker
import com.phoenix.app.ServicePlan
import com.phoenix.app.ServiceStatus
import com.phoenix.app.ServiceVeto
import com.phoenix.app.SocksAuth
import com.phoenix.app.SourceState
import com.phoenix.app.Storage
import com.phoenix.app.Tr
import com.phoenix.app.UserSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Строка таблицы серверов для окна. */
data class ServerRow(
    val link: String,
    val key: String,
    val name: String,
    val cc: String,
    val protocol: String,
    val days: Int,
    /** null — ещё не проверялся */
    val alive: Boolean?,
    val latencyMs: Long,
    val services: Map<String, ServiceStatus>,
)

data class Snapshot(val servers: List<ServerRow>, val services: List<CheckService>, val stamp: Long = System.currentTimeMillis())

data class SourceRow(val url: String, val mine: Boolean, val active: Boolean, val status: String, val lastDate: String)

data class Connection(val link: String, val name: String, val since: Long, val healthy: Boolean? = null, val note: String = "")

/** Всё, что умеет десктопная версия: сбор, проверки, подключение. Окно только показывает состояние и вызывает эти методы. */
class Engine(private val scope: CoroutineScope) {
    val settings = DesktopSettings()
    private val core = DesktopCore(CorePool({ XrayInstaller.find() }))

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress

    private val _snapshot = MutableStateFlow(Snapshot(emptyList(), emptyList(), 0))
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection

    private val _message = MutableStateFlow("")
    /** Последнее сообщение для пользователя (итог действия или ошибка). */
    val message: StateFlow<String> = _message

    private val _coreVersion = MutableStateFlow<String?>(null)
    val coreVersion: StateFlow<String?> = _coreVersion

    private val taskLock = Mutex()
    private var clientProc: XrayProc? = null
    private var proxyBefore: Map<String, String>? = null
    private var proxyChanged = false
    private var monitor: Job? = null
    private var scheduler: Job? = null

    fun storage(): Storage = Storage(Paths.context, settings.mode)

    /** Нужно один раз при старте: язык, база стран, остатки прошлого запуска. */
    fun init() {
        XrayProc.sweepRunDir()
        GeoIp.source = { Engine::class.java.getResourceAsStream("/geoip4.bin") ?: throw java.io.FileNotFoundException("geoip4.bin") }
        Tr.loader = { lang -> Engine::class.java.getResourceAsStream("/i18n/$lang.tsv")?.use { it.readBytes().toString(Charsets.UTF_8) } }
        Tr.override = settings.lang.ifBlank { null }
        NetHooks.label = { "linux" }
        refreshCoreInfo()
        scope.launch(Dispatchers.Default) { reload() }
    }

    fun refreshCoreInfo() {
        _coreVersion.value = XrayInstaller.find()?.let { XrayInstaller.version(it) ?: "установлено" }
    }

    /** После смены режима таблица и источники показывают данные другого режима: перечитываем. */
    fun markModeChanged() {
        scope.launch(Dispatchers.Default) { reload() }
    }

    fun setLanguage(lang: String) {
        settings.lang = lang
        Tr.override = lang.ifBlank { null }
    }

    // ---------- состояние ----------

    /** Перечитывает данные с диска и обновляет таблицу. */
    suspend fun reload() = withContext(Dispatchers.Default) {
        _snapshot.value = loadSnapshot()
    }

    fun loadSnapshot(): Snapshot {
        val st = storage()
        val rows = ConfigRepository.load(st)
        val checks = CheckStore(st.checksFile).load()
        val services = st.checkSelection().selected()
        val ranked = Ranking.rank(
            Ranking.candidates(
                rows, checks, history = st.history().view(NetHooks.label()), mode = st.mode,
                priority = st.priorityView(), svcIds = st.selectedServiceIds(),
            )
        )
        val byLink = rows.associateBy { it.raw }
        val list = Ranking.distinctServers(ranked).take(300).mapNotNull { c ->
            val row = byLink[c.link] ?: return@mapNotNull null
            val check = checks[CheckStore.keyOf(c.link)]
            ServerRow(
                link = c.link,
                key = CheckStore.keyOf(c.link),
                name = row.displayName,
                cc = row.cc ?: "??",
                protocol = row.protocol,
                days = row.days,
                alive = check?.let { !it.dead },
                latencyMs = check?.baselineMs ?: -1,
                services = services.associate { s -> s.id to (check?.status(s.id) ?: ServiceStatus.UNKNOWN) },
            )
        }
        return Snapshot(list, services)
    }

    fun sources(): List<SourceRow> {
        val st = storage()
        val states = HashMap<String, SourceState>()
        if (st.stateFile.exists()) st.stateFile.forEachLine { l -> SourceState.parse(l)?.let { (u, s) -> states[u] = s } }
        val mine = UserSources(st.userSourcesFile).load()
        return (DefaultSources.forMode(st.mode).map { it to false } + mine.map { it to true }).map { (u, m) ->
            val s = states[u]
            SourceRow(u, m, s?.active ?: true, s?.status.orEmpty(), s?.lastDate.orEmpty())
        }
    }

    /** @return null — добавлено, иначе текст ошибки */
    fun addSource(url: String): String? = UserSources(storage().userSourcesFile).add(url)

    fun removeSource(url: String) {
        val f = storage().userSourcesFile
        if (f.exists()) f.writeText(f.readLines().filter { it.trim() != url }.joinToString("\n") + "\n")
    }

    fun logTail(lines: Int = 200): String {
        val f = storage().logFile
        if (!f.exists()) return ""
        return com.phoenix.app.LogTrim.tailLines(f, 128 * 1024).takeLast(lines).joinToString("\n")
    }

    // ---------- задачи ----------

    /** Выполняет длинную задачу по одной: пока идёт одна, вторая не стартует. */
    private fun task(title: String, body: suspend () -> String): Job = scope.launch(Dispatchers.Default) {
        if (!taskLock.tryLock()) {
            _message.value = "Уже выполняется: ${_busy.value}"
            return@launch
        }
        _busy.value = title
        _progress.value = ""
        try {
            _message.value = body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storage().log("DESK: $title: ${e.javaClass.simpleName}: ${e.message}")
            _message.value = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _busy.value = null
            _progress.value = ""
            taskLock.unlock()
            try {
                reload()
            } catch (_: Exception) {
            }
        }
    }

    fun installCore(): Job = task("Установка ядра Xray") {
        val v = XrayInstaller.install { _progress.value = it }
        refreshCoreInfo()
        "Ядро установлено: $v"
    }

    fun collectNow(): Job = task("Сбор конфигов") { doCollect() }

    fun checkNow(): Job = task("Проверка серверов") { doChecks() }

    /** Полный цикл: сбор → живые → сервисы (и подключение, если включено). */
    fun refreshAll(): Job = task("Сбор и проверка") {
        val a = doCollect()
        val b = doChecks()
        settings.lastRunMs = System.currentTimeMillis()
        if (settings.autoConnect && _connection.value == null) bestLink()?.let { connectInternal(it.first, it.second) }
        "$a\n$b"
    }

    private suspend fun doCollect(): String {
        _progress.value = "Скачиваю источники…"
        val out = Collector(Paths.context, mode = settings.mode).run("desktop")
        return out.summary
    }

    private fun checker(storage: Storage): ServiceChecker = ServiceChecker(
        probe = { cfg, url -> core.probe(cfg, url) },
        parallel = settings.parallel,
        reach = { h, p -> ServerHosts.reach(h, p) },
        soft = { link, url -> core.reaches(link, url) },
        exit = { link -> exitOf(storage, link) },
        region = { link, url -> core.regionBlocked(link, url) },
        veto = { link, url -> ServiceVeto(storage.vetoFile).active(link, url) },
    )

    private fun exitOf(storage: Storage, link: String): String? {
        val store = ExitStore(storage.exitFile)
        val key = CheckStore.keyOf(link)
        store.fresh(key, LocalDate.now())?.let { return it }
        val cc = core.exitCountry(link) ?: return null
        store.put(key, cc, LocalDate.now().toString())
        return cc
    }

    private suspend fun doChecks(): String {
        if (XrayInstaller.find() == null) return "Ядро Xray не установлено: откройте «Настройки» и нажмите «Установить ядро»."
        val st = storage()
        val store = CheckStore(st.checksFile)
        val today = LocalDate.now().toString()
        val ch = checker(st)
        val rows = ConfigRepository.load(st)
        val rank = { Ranking.rank(Ranking.candidates(rows, store.load(), history = st.history().view(NetHooks.label()), mode = st.mode, priority = st.priorityView(), svcIds = st.selectedServiceIds(), includeDead = true)) }

        // 1) кто вообще отвечает: первые 80 по рейтингу без сегодняшней проверки
        val first = Ranking.distinctServers(rank()).filter { store.load()[CheckStore.keyOf(it.link)]?.date != today }.take(80)
        var done = 0
        if (first.isNotEmpty()) {
            _progress.value = "Проверяю доступность: ${first.size} серверов…"
            val res = ch.check(first.map { it.link }, emptyList(), today) { key, r ->
                store.putAll(mapOf(key to r))
                done++
                _progress.value = "Проверено $done из ${first.size}"
            }
            val live = res.values.count { !it.dead }
            st.log("DESK: доступность: проверено ${res.size}, отвечают $live")
        }
        // 2) выбранные сервисы у живых
        var svcDone = 0
        val services = st.checkSelection().selected()
        if (settings.checkServices && services.isNotEmpty()) {
            val todo = ServicePlan.pending(Ranking.rank(Ranking.candidates(rows, store.load(), history = st.history().view(NetHooks.label()), mode = st.mode, priority = st.priorityView(), svcIds = st.selectedServiceIds())), store.load(), services, 30)
            if (todo.isNotEmpty()) {
                _progress.value = "Проверяю сервисы у ${todo.size} серверов…"
                val res = ch.check(todo.map { it.link }, services, today) { key, r ->
                    store.putAll(mapOf(key to r))
                    svcDone++
                    _progress.value = "Сервисы: $svcDone из ${todo.size}"
                }
                st.log("DESK: сервисы ${services.joinToString(",") { it.id }}: ${res.size} серверов, рабочих ${res.values.count { !it.dead }}")
            }
        }
        val alive = store.load().values.count { !it.dead && it.date == today }
        return "Проверка завершена: новых проверок доступности $done, сервисов $svcDone; сегодня отвечают $alive серверов"
    }

    // ---------- подключение ----------

    /** Лучший сервер по рейтингу, который отвечает; null — таких нет. (ссылка, имя) */
    fun bestLink(exclude: Set<String> = emptySet()): Pair<String, String>? =
        loadSnapshot().servers.firstOrNull { it.alive == true && it.link !in exclude }?.let { it.link to it.name }

    fun connect(link: String, name: String): Job = task("Подключение") { connectInternal(link, name) }

    fun connectBest(): Job = task("Подключение") {
        val best = bestLink() ?: return@task "Нет проверенного рабочего сервера: нажмите «Собрать и проверить»."
        connectInternal(best.first, best.second)
    }

    fun disconnect(): Job = scope.launch(Dispatchers.Default) {
        stopClient()
        _message.value = "Отключено"
    }

    private fun connectInternal(link: String, name: String): String {
        val exe = XrayInstaller.find() ?: return "Ядро Xray не установлено: откройте «Настройки» и нажмите «Установить ядро»."
        stopClient()
        val st = storage()
        val socks = settings.socksPort
        val http = settings.httpPort
        val health = Ports.free()
        val auth = SocksAuth.random()
        if (Ports.open(socks) || Ports.open(http)) return "Порт $socks или $http уже занят: смените порты в «Настройках»."
        val config = try {
            ClientConfig.build(link, socks, http, health, auth, ServerHosts.hostsForLink(link), settings.bypass)
        } catch (e: Exception) {
            return "Этот конфиг не поддерживается ядром"
        }
        val proc = XrayProc(exe, config)
        if (!proc.start(socks, 8_000)) {
            return "Ядро не запустилось: " + proc.output().lines().lastOrNull().orEmpty().ifBlank { "нет вывода" }
        }
        clientProc = proc
        var note = ""
        if (settings.systemProxy) {
            if (SystemProxy.available) {
                proxyBefore = SystemProxy.snapshot()
                proxyChanged = SystemProxy.enable(socks, http)
                if (!proxyChanged) note = " Системный прокси включить не удалось — укажите SOCKS 127.0.0.1:$socks вручную."
            } else {
                note = " gsettings не найден: укажите в программах SOCKS 127.0.0.1:$socks или HTTP 127.0.0.1:$http."
            }
        }
        st.saveLastConnection(link, name)
        st.log("DESK: подключено к «$name»")
        _connection.value = Connection(link, name, System.currentTimeMillis(), null)
        startMonitor(health, auth, link)
        return "Подключено: $name.$note"
    }

    private fun stopClient() {
        monitor?.cancel()
        monitor = null
        clientProc?.stop()
        clientProc = null
        if (proxyChanged) {
            SystemProxy.restore(proxyBefore)
            proxyChanged = false
            proxyBefore = null
        }
        _connection.value = null
    }

    private fun startMonitor(healthPort: Int, auth: SocksAuth, link: String) {
        monitor?.cancel()
        monitor = scope.launch(Dispatchers.Default) {
            var bad = 0
            val tried = HashSet<String>().apply { add(link) }
            delay(3_000)
            while (isActive) {
                val ok = try {
                    LocalSocks.get(healthPort, auth, Baseline.PRIMARY, connectTimeoutMs = 6_000, readTimeoutMs = 6_000).use { it.code == 200 || it.code == 204 }
                } catch (_: Exception) {
                    false
                }
                _connection.value = _connection.value?.copy(healthy = ok)
                bad = if (ok) 0 else bad + 1
                if (!ok && clientProc?.isRunning != true) bad = 3
                if (bad >= 3 && settings.autoFailover) {
                    val st = storage()
                    st.log("DESK: сервер не отвечает, ищу другой")
                    val next = bestLink(tried)
                    if (next == null) {
                        _message.value = "Сервер перестал отвечать, запасного нет. Нажмите «Собрать и проверить»."
                        bad = 0
                    } else {
                        tried += next.first
                        val m = connectInternalFromMonitor(next.first, next.second)
                        _message.value = "Переключился на «${next.second}». $m"
                        return@launch
                    }
                }
                delay(20_000)
            }
        }
    }

    /** Переключение изнутри монитора: старый монитор завершается сам (return@launch), новый запускается в connectInternal. */
    private fun connectInternalFromMonitor(link: String, name: String): String = connectInternal(link, name)

    // ---------- расписание ----------

    fun startScheduler() {
        scheduler?.cancel()
        scheduler = scope.launch(Dispatchers.Default) {
            delay(15_000)
            while (isActive) {
                val h = settings.intervalHours
                if (h > 0 && _busy.value == null && System.currentTimeMillis() - settings.lastRunMs >= h * 3_600_000L) refreshAll()
                delay(60_000)
            }
        }
    }

    fun shutdown() {
        scheduler?.cancel()
        stopClient()
        core.close()
    }
}
