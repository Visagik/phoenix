@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSwitchIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainScreen()
                ServerSwitchHost()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSwitchIntent(intent)
    }

    /** Плитка, виджет и уведомление открывают приложение сразу со списком серверов. */
    private fun handleSwitchIntent(intent: android.content.Intent?) {
        // «Поделиться» ссылкой на подписку из другого приложения: ссылки https://… попадают в свои источники
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().take(UserSources.MAX_IMPORT_CHARS)
            val links = UserSources.parseSourceList(text).take(UserSources.MAX_IMPORT_LINKS)
            if (links.isNotEmpty()) {
                val us = UserSources(Storage(this).userSourcesFile)
                val added = us.addAll(links)
                android.widget.Toast.makeText(this, tr("Добавлено ссылок: {} из {}. Появятся в следующей сборке", added, links.size), android.widget.Toast.LENGTH_LONG).show()
            }
            intent.action = android.content.Intent.ACTION_MAIN
        }
        if (intent?.getBooleanExtra(SwitchRequest.EXTRA_SHOW, false) == true) {
            SwitchRequest.show.value = true
            intent.removeExtra(SwitchRequest.EXTRA_SHOW)
        }
    }
}

data class InstalledApp(val label: String, val pkg: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    @Volatile
    private var storage = Storage(app)
    /** Настройки той области, которую сейчас правят на экране «Подключение»: общие или отдельные для режима. */
    private var settings = AppSettings(app)

    private val _mode = MutableStateFlow(storage.mode)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _listUrl = MutableStateFlow("")
    val listUrl: StateFlow<String> = _listUrl.asStateFlow()

    private val _checks = MutableStateFlow<Map<String, CheckResult>>(emptyMap())
    val checks: StateFlow<Map<String, CheckResult>> = _checks.asStateFlow()

    private val _selectedServices = MutableStateFlow(CheckServices.ALL)
    val selectedServices: StateFlow<List<CheckService>> = _selectedServices.asStateFlow()

    private val _allServices = MutableStateFlow(storage.checkSelection().all())
    /** Сервисы, показанные в списке проверки (встроенные без убранных и свои). */
    val allServices: StateFlow<List<CheckService>> = _allServices.asStateFlow()

    private val _hiddenCount = MutableStateFlow(storage.hiddenServices().load().size)
    val hiddenCount: StateFlow<Int> = _hiddenCount.asStateFlow()

    private val _customServices = MutableStateFlow(storage.customServices().load())
    val customServices: StateFlow<List<CheckService>> = _customServices.asStateFlow()

    private val _autoServices = MutableStateFlow(settings.autoServiceCheck)
    val autoServices: StateFlow<Boolean> = _autoServices.asStateFlow()


    private val _checkStatus = MutableStateFlow("")
    val checkStatus: StateFlow<String> = _checkStatus.asStateFlow()

    private val _bypassUser = MutableStateFlow<Set<String>>(emptySet())
    val bypassUser: StateFlow<Set<String>> = _bypassUser.asStateFlow()

    private val _bypassFound = MutableStateFlow<List<String>>(emptyList())
    /** Российские приложения, найденные на телефоне автоматически и идущие в обход. */
    val bypassFound: StateFlow<List<String>> = _bypassFound.asStateFlow()

    private val _presetOn = MutableStateFlow(true)
    val presetOn: StateFlow<Boolean> = _presetOn.asStateFlow()

    private var checkJob: Job? = null

    private val _appLinksOn = MutableStateFlow(true)
    val appLinksOn: StateFlow<Boolean> = _appLinksOn.asStateFlow()

    fun setAppLinks(on: Boolean) {
        BypassStore(storage.base, storage.mode).appLinksEnabled = on
        _appLinksOn.value = on
        PhoenixVpnService.applyBypass(getApplication())
    }

    private val _hevOn = MutableStateFlow(settings.hevEngine)
    val hevOn: StateFlow<Boolean> = _hevOn.asStateFlow()
    val hevAvailable: Boolean get() = HevTunnel.available

    fun setHev(on: Boolean) {
        settings.hevEngine = on
        _hevOn.value = on
    }

    private val _openOn = MutableStateFlow(false)
    /** Включён ли обход «не заблокированное в РФ» для текущего режима. */
    val openOn: StateFlow<Boolean> = _openOn.asStateFlow()

    private val _places = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Для каждого конфига (по личности): где он работал и где нет — «Wi-Fi 12✓/1✗ · моб. мтс 0✓/5✗». */
    val places: StateFlow<Map<String, String>> = _places.asStateFlow()

    private val _slowSwitch = MutableStateFlow(settings.slowSwitch)
    val slowSwitch: StateFlow<Boolean> = _slowSwitch.asStateFlow()
    private val _slowMin = MutableStateFlow(settings.slowMinMbps)
    val slowMin: StateFlow<Float> = _slowMin.asStateFlow()

    private val _scope = MutableStateFlow(AppSettings.Scope.AUTO)
    /** Какую область настроек сейчас правят: общие, белые списки или чёрные списки. */
    val scope: StateFlow<AppSettings.Scope> = _scope.asStateFlow()

    private val _separate = MutableStateFlow(Mode.entries.associateWith { settings.isSeparate(it) })
    /** У каких режимов включены «свои настройки». */
    val separate: StateFlow<Map<Mode, Boolean>> = _separate.asStateFlow()

    fun setScope(sc: AppSettings.Scope) {
        _scope.value = sc
        settings = AppSettings(getApplication<Application>(), sc)
        reloadSettingsFlows()
    }

    /** Названия настроек режима, которые отличаются от общих (пусто, если у режима нет своих). */
    fun overridden(mode: Mode): List<String> = try { settings.overriddenLabels(mode) } catch (_: Throwable) { emptyList() }

    fun resetOverrides(mode: Mode) {
        settings.resetOverrides(mode)
        _separate.value = Mode.entries.associateWith { settings.isSeparate(it) }
        reloadSettingsFlows()
    }

    fun setSeparate(mode: Mode, on: Boolean) {
        settings.setSeparate(mode, on)
        _separate.value = Mode.entries.associateWith { settings.isSeparate(it) }
        reloadSettingsFlows()
    }

    private val _blockQuic = MutableStateFlow(settings.blockQuic)
    val blockQuic: StateFlow<Boolean> = _blockQuic.asStateFlow()

    fun setBlockQuic(v: Boolean) {
        settings.blockQuic = v
        _blockQuic.value = v
    }

    private val _autoRecheck = MutableStateFlow(settings.autoRecheck)
    val autoRecheck: StateFlow<Boolean> = _autoRecheck.asStateFlow()

    fun setAutoRecheck(v: Boolean) {
        settings.autoRecheck = v
        _autoRecheck.value = v
    }

    private val _scheduleKind = MutableStateFlow(settings.scheduleKind)
    val scheduleKind: StateFlow<ScheduleKind> = _scheduleKind.asStateFlow()
    private val _intervalHours = MutableStateFlow(settings.intervalHours)
    val intervalHours: StateFlow<Int> = _intervalHours.asStateFlow()

    fun setScheduleKind(k: ScheduleKind) {
        settings.scheduleKind = k
        _scheduleKind.value = k
        Scheduler.reschedule(getApplication<Application>())
    }

    fun setIntervalHours(h: Int) {
        settings.intervalHours = h
        _intervalHours.value = settings.intervalHours
        Scheduler.reschedule(getApplication<Application>())
    }

    private val _sourceStats = MutableStateFlow<List<SourceStat>>(emptyList())
    /** Доля рабочих конфигов по каждой ссылке-источнику (для редактируемого режима). */
    val sourceStats: StateFlow<List<SourceStat>> = _sourceStats.asStateFlow()

    private val _sourcePrio = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Приоритеты ссылок-источников редактируемого режима (−1 низкий, 1 высокий; обычные не хранятся). */
    val sourcePrio: StateFlow<Map<String, Int>> = _sourcePrio.asStateFlow()

    fun setSourcePriority(url: String, level: Int) {
        SourcePriorities(srcStorage.sourcePriorityFile).set(url, level)
        _sourcePrio.value = SourcePriorities(srcStorage.sourcePriorityFile).load()
    }

    /** Запустить принудительную перепроверку всех конфигов (в фоне). */
    fun recheckAll() = Recheck.enqueue(getApplication<Application>(), storage.mode)

    /** Перепроверить только приложения у рабочих конфигов (быстро, без полной перепроверки). */
    fun oddsLookup(): (String, String) -> Int? = CheckStore(storage.checksFile).odds()

    fun exitLookup(): (String) -> String? = ExitStore(storage.exitFile).load().let { m -> { key -> m[key]?.cc } }

    fun recheckApps() = Recheck.enqueueServices(getApplication<Application>(), storage.mode)

    fun setSlowSwitch(v: Boolean) {
        settings.slowSwitch = v
        _slowSwitch.value = v
    }

    fun setSlowMin(v: Float) {
        settings.slowMinMbps = v
        _slowMin.value = settings.slowMinMbps
    }

    fun setOpenPreset(on: Boolean) {
        BypassStore(storage.base, storage.mode).openEnabled = on
        _openOn.value = on
        PhoenixVpnService.applyBypass(getApplication())
    }

    private val _backupStatus = MutableStateFlow("")
    val backupStatus: StateFlow<String> = _backupStatus.asStateFlow()

    /** Записывает резервную копию (настройки + данные обоих режимов) в выбранный пользователем файл. */
    fun exportBackup(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = try {
                val stamp = java.time.OffsetDateTime.now().toString()
                val text = Backup.export(app.filesDir, settings.exportMap(), stamp)
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw java.io.IOException(tr("не удалось открыть файл"))
                tr("Копия сохранена ({} строк)", text.lines().size)
            } catch (e: Exception) {
                tr("Не удалось сохранить: {}", e.message)
            }
        }
    }

    /** Читает копию и объединяет с текущими данными: ничего не стирается, чёрный список только пополняется. */
    fun importBackup(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = try {
                val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw java.io.IOException(tr("не удалось открыть файл"))
                if (text.length > Backup.MAX_IMPORT_CHARS) throw java.io.IOException(tr("файл слишком большой для копии Phoenix"))
                val report = Backup.import(app.filesDir, text)
                try { settings.importMap(report.settings) } catch (e: Exception) { storage.log("BACKUP: настройки из копии не применены: ${e.message}") }
                try { reloadSettingsFlows() } catch (e: Exception) { storage.log("BACKUP: не удалось обновить настройки на экране: ${e.message}") }
                refresh()
                PlanBus.notifyChanged()
                storage.log("BACKUP: импорт — " + report.notes.joinToString("; "))
                tr("Импорт выполнен:\n") + report.text()
            } catch (e: BackupFormatException) {
                tr("Не удалось: {}", e.message)
            } catch (e: Exception) {
                tr("Не удалось прочитать файл: {}", e.message)
            }
        }
    }

    private fun reloadSettingsFlows() {
        _autoMode.value = settings.autoMode
        _autoReconnect.value = settings.autoReconnect
        _globalPolicy.value = settings.globalPolicy
        _speedOnMetered.value = settings.speedOnMetered
        _autoConnect.value = settings.autoConnect
        _ipv6.value = settings.ipv6Mode
        _killSwitch.value = settings.killSwitch
        _slowSwitch.value = settings.slowSwitch
        _slowMin.value = settings.slowMinMbps
        _runTime.value = settings.timeText()
        _onlyCharging.value = settings.onlyCharging
        _blockQuic.value = settings.blockQuic
        _autoRecheck.value = settings.autoRecheck
        _autoServices.value = settings.autoServiceCheck
        _scheduleKind.value = settings.scheduleKind
        _intervalHours.value = settings.intervalHours
        _separate.value = Mode.entries.associateWith { settings.isSeparate(it) }
    }

    /** Для какого режима сейчас редактируются источники (не обязательно активного). */
    private val _srcMode = MutableStateFlow(storage.mode)
    val srcMode: StateFlow<Mode> = _srcMode.asStateFlow()
    private val srcStorage: Storage get() = Storage(getApplication<Application>(), _srcMode.value)

    fun setSourcesMode(m: Mode) {
        _srcMode.value = m
        viewModelScope.launch(Dispatchers.IO) { reloadSources() }
    }

    private val _rows = MutableStateFlow<List<ConfigRow>>(emptyList())
    val rows: StateFlow<List<ConfigRow>> = _rows.asStateFlow()

    private val _report = MutableStateFlow("")
    val report: StateFlow<String> = _report.asStateFlow()

    private val _mySources = MutableStateFlow<List<String>>(emptyList())
    val mySources: StateFlow<List<String>> = _mySources.asStateFlow()

    private val _blacklist = MutableStateFlow<List<Blacklist.Entry>>(emptyList())
    val blacklist: StateFlow<List<Blacklist.Entry>> = _blacklist.asStateFlow()

    private val _proposals = MutableStateFlow<List<Proposal>>(emptyList())
    val proposals: StateFlow<List<Proposal>> = _proposals.asStateFlow()

    private val _runTime = MutableStateFlow(settings.timeText())
    val runTime: StateFlow<String> = _runTime.asStateFlow()

    private val _onlyCharging = MutableStateFlow(settings.onlyCharging)
    val onlyCharging: StateFlow<Boolean> = _onlyCharging.asStateFlow()

    val runHour: Int get() = settings.runHour
    val runMinute: Int get() = settings.runMinute

    // ---- подключение, режим, цели ----
    private val _autoMode = MutableStateFlow(settings.autoMode)
    val autoMode: StateFlow<Boolean> = _autoMode.asStateFlow()

    private val _autoReconnect = MutableStateFlow(settings.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _globalPolicy = MutableStateFlow(settings.globalPolicy)
    val globalPolicy: StateFlow<Policy> = _globalPolicy.asStateFlow()

    private val _speedOnMetered = MutableStateFlow(settings.speedOnMetered)
    val speedOnMetered: StateFlow<Boolean> = _speedOnMetered.asStateFlow()

    private val _detectText = MutableStateFlow(settings.lastDetect)
    val detectText: StateFlow<String> = _detectText.asStateFlow()

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: StateFlow<List<Target>> = _targets.asStateFlow()

    private val _bypassSites = MutableStateFlow<List<String>>(emptyList())
    val bypassSites: StateFlow<List<String>> = _bypassSites.asStateFlow()

    private val _busy = MutableStateFlow("")
    val busy: StateFlow<String> = _busy.asStateFlow()

    fun setAutoMode(v: Boolean) {
        settings.autoMode = v
        _autoMode.value = v
    }

    fun setAutoReconnect(v: Boolean) {
        settings.autoReconnect = v
        _autoReconnect.value = v
    }

    fun setGlobalPolicy(p: Policy) {
        settings.globalPolicy = p
        _globalPolicy.value = p
        PlanBus.notifyChanged()
    }

    fun setSpeedOnMetered(v: Boolean) {
        settings.speedOnMetered = v
        _speedOnMetered.value = v
    }

    /** Определить тип блокировок прямо сейчас (результат покажется в тексте и, при авторежиме, сменит режим). */
    fun detectNow() {
        if (_busy.value.isNotEmpty()) return
        val app = getApplication<Application>()
        _busy.value = tr("Определяю тип блокировок…")
        viewModelScope.launch(Dispatchers.IO) {
            val out = ModeDetector.run(app, ignoreHold = true)
            _detectText.value = out.text
            val newMode = Mode.read(app.filesDir)
            if (newMode != storage.mode) {
                storage = Storage(app)
                _mode.value = newMode
                _rows.value = emptyList()
                _checks.value = emptyMap()
                refresh()
                PlanBus.notifyChanged()
            }
            _busy.value = ""
        }
    }

    // цели
    fun reloadTargets() {
        _targets.value = TargetStore(storage.targetsFile).load()
        _bypassSites.value = BypassStore(storage.base, storage.mode).userSites()
    }

    /** @return null — добавлено, иначе текст ошибки */
    fun addTargetApp(pkg: String, label: String, checkUrl: String): String? {
        // у любого приложения есть адрес проверки: свой, известный или общий (проверка, что сервер вообще работает)
        val url = checkUrl.trim().ifBlank { KnownApps.urlOrDefault(pkg) }
        if (!url.startsWith("http", ignoreCase = true)) return tr("Адрес проверки должен начинаться с https://")
        val err = TargetStore(storage.targetsFile).add(Target.app(pkg, label, url))
        reloadTargets()
        PlanBus.notifyChanged()
        return err
    }

    fun addTargetSite(input: String): String? {
        val t = Target.site(input) ?: return tr("Не похоже на адрес сайта")
        val err = TargetStore(storage.targetsFile).add(t)
        reloadTargets()
        PlanBus.notifyChanged()
        return err
    }

    fun removeTarget(id: String) {
        TargetStore(storage.targetsFile).remove(id)
        reloadTargets()
        PlanBus.notifyChanged()
    }

    /** Меняет адрес проверки цели; пусто — вернуть известный или общий. @return null — сохранено, иначе текст ошибки */
    fun setTargetUrl(t: Target, url: String): String? {
        val u = url.trim().ifBlank { if (t.kind == TargetKind.APP) KnownApps.urlOrDefault(t.value) else "https://${t.value}/" }
        if (!u.startsWith("http", ignoreCase = true)) return tr("Адрес проверки должен начинаться с https://")
        TargetStore(storage.targetsFile).update(t.copy(checkUrl = u))
        reloadTargets()
        return null
    }

    fun setTargetPolicy(t: Target, p: Policy) {
        TargetStore(storage.targetsFile).setPolicy(t.id, p)
        reloadTargets()
        PlanBus.notifyChanged()
    }

    fun verifyTargetsNow() {
        if (_busy.value.isNotEmpty()) return
        if (!XrayCore.available) {
            _busy.value = ""
            _checkStatus.value = XrayCore.problem
            return
        }
        val app = getApplication<Application>()
        _busy.value = tr("Проверяю серверы для целей…")
        viewModelScope.launch {
            val text = TargetsRunner.run(app, withSpeed = false)
            _busy.value = ""
            _checkStatus.value = tr("Готово: {}", text)
            refresh()
        }
    }

    fun addBypassSite(input: String): String? {
        val err = BypassStore(storage.base, storage.mode).addSite(input)
        reloadTargets()
        return err
    }

    fun removeBypassSite(host: String) {
        BypassStore(storage.base, storage.mode).removeSite(host)
        reloadTargets()
    }

    /** Замер скорости через конфиг. */
    fun measureSpeed(link: String) {
        if (_busy.value.isNotEmpty()) return
        if (!XrayCore.available) {
            _checkStatus.value = XrayCore.problem
            return
        }
        val app = getApplication<Application>()
        val st = storage
        _busy.value = tr("Измеряю скорость…")
        viewModelScope.launch(Dispatchers.IO) {
            val r = SpeedRunner.run(app, link)
            if (r != null) {
                CheckStore(st.checksFile).putSpeed(CheckStore.keyOf(link), r, LocalDate.now().toString(), ConfigIdentity.address(link))
                if (st === storage) _checks.value = CheckStore(st.checksFile).load()
                _checkStatus.value = tr("Скорость: {}", SpeedTest.format(r.mbps))
            } else {
                _checkStatus.value = tr("Скорость измерить не удалось")
            }
            _busy.value = ""
        }
    }

    // ---- автоподключение, IPv6, защита от утечек ----
    private val _autoConnect = MutableStateFlow(settings.autoConnect)
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    private val _ipv6 = MutableStateFlow(settings.ipv6Mode)
    val ipv6: StateFlow<Ipv6Mode> = _ipv6.asStateFlow()

    private val _killSwitch = MutableStateFlow(settings.killSwitch)
    val killSwitch: StateFlow<Boolean> = _killSwitch.asStateFlow()

    fun setAutoConnect(v: Boolean) {
        settings.autoConnect = v
        _autoConnect.value = v
    }

    fun setIpv6(m: Ipv6Mode) {
        settings.ipv6Mode = m
        _ipv6.value = m
    }

    fun setKillSwitch(v: Boolean) {
        settings.killSwitch = v
        _killSwitch.value = v
    }

    // ---- журнал ----
    /** Строки журнала обоих режимов, новые внизу. [prefix] — фильтр по началу сообщения (VPN:, DETECT:…), пусто — всё. */
    fun logLines(prefix: String, limit: Int = 400): List<String> {
        val app = getApplication<Application>()
        val lines = Mode.entries.flatMap { m ->
            val f = Storage(app, m).logFile
            if (f.exists()) LogTrim.tailLines(f) else emptyList()
        }.filter { it.isNotBlank() }
        val filtered = if (prefix.isBlank()) lines else lines.filter { line -> line.substringAfter(' ', "").startsWith(prefix) }
        return filtered.sortedBy { it.substringBefore(' ') }.takeLast(limit)
    }

    /** Все строки журнала обоих режимов за последние сутки (старые сверху). */
    fun logDay(): List<String> {
        val since = java.time.OffsetDateTime.now().minusDays(1).toString()
        return Diagnostics.since(logLines("", 40_000), since)
    }

    /** Лента «Что произошло?» за последние 15 минут из журнала текущего режима. */
    fun recentEvents(): List<EventTimeline.Item> = try {
        EventTimeline.build(LogTrim.tailLines(storage.logFile, 400_000), java.time.OffsetDateTime.now(), java.time.ZoneId.systemDefault())
    } catch (_: Throwable) { emptyList() }

    /** Сколько работы приложение сделало само сегодня и вчера (оценка расхода на проверки). */
    fun energyLines(): List<String> = try {
        val m = EnergyMeter.of(java.io.File(getApplication<Application>().filesDir, "energy.tsv"))
        m.save(System.currentTimeMillis())
        val today = LocalDate.now()
        listOf(tr("Сегодня: ") + m.text(today.toString()), tr("Вчера: ") + m.text(today.minusDays(1).toString()))
    } catch (_: Throwable) { emptyList() }

    /** Статистика работы за последнюю неделю для экрана «Состояние». */
    fun statLines(): List<String> = try {
        val st = UsageStats.of(storage.statsFile)
        st.save(System.currentTimeMillis())
        StatsText.lines(st.lastDays(LocalDate.now(), 7))
    } catch (_: Throwable) { emptyList() }

    /** Автоматические изменения последних суток, которые можно вернуть (аудит источников, авто-приоритеты). */
    fun undoEntries(): List<UndoStore.Entry> = try { UndoStore(storage.undoDir).available(System.currentTimeMillis()) } catch (_: Throwable) { emptyList() }

    fun undo(id: String): Boolean = try { UndoStore(storage.undoDir).restore(id, System.currentTimeMillis()) } catch (_: Throwable) { false }

    /** Сводка «что сейчас» для раздела «Диагностика». */
    fun diagSummary(): List<Pair<String, String>> {
        val app = getApplication<Application>()
        val st = VpnState.status.value
        val today = LocalDate.now()
        val checks = CheckStore(storage.checksFile).load()
        val fresh = checks.values.filter { it.date >= today.minusDays(1).toString() }
        val alive = fresh.count { !it.dead }
        val out = ArrayList<Pair<String, String>>()
        out += tr("Версия") to (try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" })
        out += tr("Режим") to (tr(storage.mode.title) + if (settings.autoMode) tr(" (определяется автоматически)") else tr(" (вручную)"))
        out += tr("Сеть") to NetInfo.label(app)
        try {
            val view = storage.history().view(NetInfo.label(app))
            val rates = ProtocolLearning.rates(ConfigRepository.load(storage).map { it.raw to view(ConfigIdentity.key(it.raw)) })
            ProtocolLearning.summary(rates).takeIf { it.isNotEmpty() }?.let { out += tr("Что работает в этой сети") to it }
        } catch (_: Throwable) {
        }
        out += "VPN" to when (st.phase) {
            VpnPhase.CONNECTED -> tr("подключён: {}", st.title) + if (st.note.isNotBlank()) " · ${st.note}" else ""
            VpnPhase.CONNECTING -> tr("подключается: {}", st.title)
            VpnPhase.ERROR -> tr("ошибка: {}", st.error)
            VpnPhase.IDLE -> tr("отключён")
        }
        out += tr("Последнее определение режима") to settings.lastDetect.ifBlank { tr("ещё не было") }
        val re = settings.lastRecheckAt
        out += tr("Перепроверка конфигов") to if (re <= 0) tr("ещё не было") else tr("{} мин назад", (System.currentTimeMillis() - re) / 60_000)
        out += tr("Свежие проверки (сутки)") to tr("живых {} из {}", alive, fresh.size)
        val svc = storage.checkSelection().selected()
        val odds = CheckStore(storage.checksFile).odds()
        for (t in storage.let { TargetStore(it.targetsFile).load() }) {
            val first = t.servers.firstOrNull()
            val name = if (t.isDirect) tr("напрямую, мимо VPN") else first?.let { LinkParser.parse(it)?.title?.let { s -> urlDecode(s) } }.orEmpty().ifBlank { tr("нет") }
            val res = first?.let { checks[CheckStore.keyOf(it)] }
            val marks = res?.let { r -> " · " + r.labelNamed(svc, odds?.let { f -> { sid -> f(CheckStore.keyOf(first), sid) } }) }.orEmpty()
            out += tr("Цель «{}»", t.label) to tr("{} (запасных {}, проверено {}){}", name, maxOf(0, t.servers.size - 1), t.verifiedAt.ifBlank { "—" }, marks)
        }
        return out
    }

    fun clearLogs() {
        val app = getApplication<Application>()
        Mode.entries.forEach { m -> Storage(app, m).logFile.takeIf { it.exists() }?.writeTextAtomic("") }
    }

    init {
        // Обновляем всё при старте и каждый раз, когда прогон заканчивается.
        viewModelScope.launch {
            RunState.running.collect { running -> if (!running) refresh() }
        }
        // Служба VPN сама меняет режим (белые/чёрные списки) — главный экран должен показывать тот, что действует сейчас.
        viewModelScope.launch {
            VpnState.status.collect { syncModeFromDisk() }
        }
        viewModelScope.launch {
            Mode.changes.collect { syncModeFromDisk() }
        }
        viewModelScope.launch {
            RecheckState.running.collect { running -> if (!running) { refresh(); reloadSources() } }
        }
    }

    fun syncModeFromDisk() {
        val app = getApplication<Application>()
        val now = Mode.read(app.filesDir)
        if (now == storage.mode) return
        storage = Storage(app)
        _mode.value = now
        _rows.value = emptyList()
        _checks.value = emptyMap()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _rows.value = ConfigRepository.load(storage)
            _report.value = ConfigRepository.reportText(storage)
            _checks.value = CheckStore(storage.checksFile).load()
            _selectedServices.value = storage.checkSelection().selected()
            _customServices.value = storage.customServices().load()
            _allServices.value = storage.checkSelection().all()
            _hiddenCount.value = storage.hiddenServices().load().size
            val bypass = BypassStore(storage.base, storage.mode)
            _bypassUser.value = bypass.userApps()
            _presetOn.value = bypass.presetEnabled
            _bypassFound.value = bypass.discovered(installedPackages())
            _openOn.value = bypass.openEnabled
            _appLinksOn.value = bypass.appLinksEnabled
            _places.value = storage.history().placesByIdentity().mapValues { NetLabel.summary(it.value) }
            reloadSources()
            reloadTargets()
        }
    }

    /** Переключение режима: у каждого режима свои источники, чёрный список, статистика и список обхода VPN. */
    fun setMode(m: Mode) {
        if (m == storage.mode) return
        val app = getApplication<Application>()
        Mode.write(app.filesDir, m)
        AppSettings(app).let { it.manualModeAt = System.currentTimeMillis(); it.manualModeNet = NetInfo.label(app) }
        storage = Storage(app)
        _mode.value = m
        _rows.value = emptyList()
        _report.value = ""
        _checks.value = emptyMap()
        refresh()
        PlanBus.notifyChanged() // работающий VPN перейдёт на сервер нового режима
    }

    /** Адрес общего списка для режима чёрных списков. @return null — сохранено, иначе текст ошибки */
    fun setListUrl(url: String): String? {
        val u = url.trim()
        if (u.isNotEmpty() && !UserSources.isValid(u)) return tr("Нужна ссылка вида https://…")
        srcStorage.setSourcesListUrl(u)
        reloadSources()
        return null
    }

    // ---- проверка сервисов ----
    fun toggleService(id: String) {
        val ids = _selectedServices.value.map { it.id }.toMutableSet()
        if (!ids.add(id)) ids.remove(id)
        storage.checkSelection().set(ids)
        _selectedServices.value = storage.checkSelection().selected()
    }

    /** @return null — добавлено, иначе текст ошибки. Новый сервис сразу выбран для проверки. */
    fun addCustomService(title: String, address: String): String? {
        val err = storage.customServices().add(title, address)
        if (err != null) return err
        val added = storage.customServices().load().last()
        val ids = _selectedServices.value.map { it.id }.toMutableSet().apply { add(added.id) }
        storage.checkSelection().set(ids)
        _selectedServices.value = storage.checkSelection().selected()
        _customServices.value = storage.customServices().load()
        return null
    }

    /** Убрать сервис из списка проверки: встроенный прячется (его можно вернуть), свой удаляется. */
    fun removeService(id: String) {
        if (_customServices.value.any { it.id == id }) { removeCustomService(id); return }
        storage.hiddenServices().hide(id)
        val ids = _selectedServices.value.map { it.id }.toMutableSet().apply { remove(id) }
        storage.checkSelection().set(ids)
        reloadServices()
    }

    fun restoreServices() {
        val hidden = storage.hiddenServices().load()
        storage.hiddenServices().restoreAll()
        val ids = _selectedServices.value.map { it.id }.toMutableSet().apply { addAll(hidden) }
        storage.checkSelection().set(ids)
        reloadServices()
    }

    private fun reloadServices() {
        _selectedServices.value = storage.checkSelection().selected()
        _customServices.value = storage.customServices().load()
        _allServices.value = storage.checkSelection().all()
        _hiddenCount.value = storage.hiddenServices().load().size
    }

    fun removeCustomService(id: String) {
        storage.customServices().remove(id)
        val ids = _selectedServices.value.map { it.id }.toMutableSet().apply { remove(id) }
        storage.checkSelection().set(ids)
        _selectedServices.value = storage.checkSelection().selected()
        _customServices.value = storage.customServices().load()
    }

    fun setAutoServices(v: Boolean) {
        settings.autoServiceCheck = v
        _autoServices.value = v
    }

    fun runChecks(links: List<String>) {
        if (checkJob?.isActive == true) return
        if (!XrayCore.available) {
            _checkStatus.value = XrayCore.problem
            return
        }
        if (links.isEmpty() || _selectedServices.value.isEmpty()) {
            _checkStatus.value = tr("Нечего проверять: нет конфигов или не выбран ни один сервис")
            return
        }
        val app = getApplication<Application>()
        val st = storage
        val services = _selectedServices.value
        checkJob = viewModelScope.launch {
            val done = AtomicInteger()
            val total = links.size
            _checkStatus.value = tr("Проверяю 0/{}…", total)
            val checker = ServiceChecker({ cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(app, cfg, url) } }, parallel = CheckLimits.parallel(app), reach = { h, p -> ServerHosts.reach(h, p) }, soft = { l, u -> SpeedRunner.reaches(app, l, u) }, exit = { l -> SpeedRunner.exitCountry(app, l) }, region = { l, u -> SpeedRunner.regionBlocked(app, l, u) }, veto = { l, u -> ServiceVeto(Storage(app).vetoFile).active(l, u) })
            val store = CheckStore(st.checksFile)
            val results = checker.check(links, services, LocalDate.now().toString()) { key, res ->
                store.putAll(mapOf(key to res))
                _checkStatus.value = tr("Проверяю {}/{}…", done.incrementAndGet(), total)
            }
            if (st === storage) _checks.value = store.load()
            val alive = results.values.count { !it.dead }
            st.log("CHECK: проверено ${results.size}, живых $alive" + if (alive == 0) "; последняя ошибка ядра: ${XrayCore.lastError.ifBlank { "нет" }}" else "")
            _checkStatus.value = if (alive == 0 && XrayCore.lastError.isNotBlank())
                tr("Ядро не запустилось: {}", XrayCore.lastError)
            else tr("Готово: живых {} из {}", alive, results.size)
        }
    }

    // ---- обход VPN ----
    fun setPreset(on: Boolean) {
        BypassStore(storage.base, storage.mode).presetEnabled = on
        _presetOn.value = on
        PhoenixVpnService.applyBypass(getApplication())
    }

    private fun installedPackages(): List<String> = try {
        getApplication<Application>().packageManager.getInstalledApplications(0).map { it.packageName }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Убрать найденное автоматически приложение из обхода (больше не добавляется). */
    fun excludeFoundApp(pkg: String) {
        val b = BypassStore(storage.base, storage.mode)
        b.excludeAuto(pkg)
        _bypassFound.value = b.discovered(installedPackages())
        PhoenixVpnService.applyBypass(getApplication())
    }

    fun setBypassApps(packages: Set<String>) {
        BypassStore(storage.base, storage.mode).setUserApps(packages)
        _bypassUser.value = packages
        PhoenixVpnService.applyBypass(getApplication())
    }

    /** Установленные приложения с иконкой в списке запуска. Вызывать вне главного потока. */
    fun installedApps(): List<InstalledApp> {
        val pm = getApplication<Application>().packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0)
        return infos.map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != getApplication<Application>().packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun reloadSources() {
        _listUrl.value = srcStorage.sourcesListUrl()
        _mySources.value = UserSources(srcStorage.userSourcesFile).load()
        val bl = Blacklist(srcStorage.blacklistFile).load()
        _blacklist.value = bl
        val keep = KeepList(srcStorage.keepFile).load()
        val blocked = bl.map { it.url }.toSet()
        _proposals.value = if (srcStorage.proposalsFile.exists())
            SourceAudit.parse(srcStorage.proposalsFile.readText()).filter { it.url !in blocked && it.url !in keep }
        else emptyList()
        _sourceStats.value = computeSourceStats(srcStorage)
        _sourcePrio.value = SourcePriorities(srcStorage.sourcePriorityFile).load()
    }

    /** Живой / неживой / неизвестно по каждому конфигу: свежая проверка (до 7 дней) или опыт в текущей сети. */
    private fun computeSourceStats(st: Storage): List<SourceStat> = try {
        if (!st.sourceMembersFile.exists()) emptyList()
        else {
            val members = SourceStats.decode(st.sourceMembersFile.readText())
            val limit = LocalDate.now().minusDays(7).toString()
            val checks = CheckStore(st.checksFile).load()
            val ctx = NetInfo.label(getApplication<Application>())
            val hist = HashMap<String, HistStat>()
            for ((key, stat) in st.history().load()) {
                val tab = key.indexOf('\t')
                if (tab > 0 && key.substring(tab + 1) == ctx) hist[SourceStats.idOfIdentity(key.substring(0, tab))] = stat
            }
            SourceStats.compute(members) { id ->
                val c = checks[id]
                val h = hist[id]
                when {
                    c != null && !c.dead && c.date >= limit -> CfgStatus.ALIVE
                    h != null && h.total > 0 && h.ok > 0 && h.reliability >= 0.5 -> CfgStatus.ALIVE
                    c != null && c.dead && c.date >= limit -> CfgStatus.DEAD
                    h != null && h.total > 0 -> CfgStatus.DEAD
                    else -> CfgStatus.UNKNOWN
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun runNow() = Scheduler.runNow(getApplication<Application>())

    // ---- свои источники ----
    /** @return null — добавлено, иначе текст ошибки */
    fun addUserSource(url: String): String? {
        val err = UserSources(srcStorage.userSourcesFile).add(url)
        reloadSources()
        return err
    }

    fun removeUserSource(url: String) {
        UserSources(srcStorage.userSourcesFile).remove(url)
        reloadSources()
    }

    // ---- чёрный список и аудит ----
    fun blacklistAdd(url: String): String? {
        val u = url.trim()
        if (!UserSources.isValid(u)) return tr("Нужна ссылка вида https://…")
        val ok = Blacklist(srcStorage.blacklistFile).add(u, "добавлено вручную")
        KeepList(srcStorage.keepFile).remove(u)
        reloadSources()
        return if (ok) null else tr("Эта ссылка уже в чёрном списке")
    }

    fun unblacklist(url: String) {
        Blacklist(srcStorage.blacklistFile).remove(url)
        KeepList(srcStorage.keepFile).add(url) // аудит больше не будет предлагать убрать её снова
        reloadSources()
    }

    /** Применяет предложения аудита (не больше SourceAudit.MAX_APPLY за раз). @return сколько добавлено */
    fun applyProposals(): Int {
        val bl = Blacklist(srcStorage.blacklistFile)
        val high = SourcePriorities(srcStorage.sourcePriorityFile).load().filterValues { it >= SourcePriority.HIGH }.keys
        val chosen = SourceAudit.select(_proposals.value.filter { it.url !in high }, bl.load(), SourceAudit.MAX_APPLY)
        chosen.forEach { bl.add(it.url, it.reason) }
        val rest = _proposals.value.filter { p -> chosen.none { it.url == p.url } }
        srcStorage.proposalsFile.writeTextAtomic(SourceAudit.serialize(rest))
        reloadSources()
        return chosen.size
    }

    // ---- настройки ----
    fun setRunTime(hour: Int, minute: Int) {
        settings.runHour = hour
        settings.runMinute = minute
        _runTime.value = settings.timeText()
        Scheduler.reschedule(getApplication<Application>())
    }

    fun setOnlyCharging(value: Boolean) {
        settings.onlyCharging = value
        _onlyCharging.value = value
        Scheduler.reschedule(getApplication<Application>())
    }

    /** Только отчёт (без журнала) — для сохранения в файл. */
    fun reportOnlyText(): String =
        LogMask.applyAll(storage.latestReport()?.readText().orEmpty())

    fun enrichedText(): String =
        storage.latestEnriched()?.takeIf { it.exists() }?.readText().orEmpty()
}

private enum class Page(val title: String, val hint: String) {
    STATE("Состояние", "права галочками, что сейчас, размер журнала"),
    MODE("Режим блокировок", "белые/чёрные списки, определение автоматически"),
    TARGETS("Приложения и сайты", "запасные серверы, вариант А или Б"),
    BYPASS("Обход VPN и проверка сервисов", "российские приложения и сайты, свои приложения"),
    CONNECTION("Подключение", "автоподключение, IPv6, защита от утечек, плитка в шторке"),
    COLLECT("Автосбор", "время или интервал сбора, только при зарядке, статус последнего запуска"),
    FIRMWARE("Фоновая работа (прошивка)", "все разрешения для фона в одном месте: батарея, будильники, автозапуск"),
    SOURCES("Источники", "ссылки для белых и чёрных списков, чёрный список, аудит"),
    BACKUP("Экспорт и импорт", "резервная копия настроек, целей, обхода, источников и журнала"),
    DIAG("Диагностика", "сводка «что сейчас», журнал с фильтрами и поиском, отчёт сбора"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val mode by vm.mode.collectAsStateWithLifecycle()
    val resumeOwner = LocalLifecycleOwner.current
    DisposableEffect(resumeOwner) {
        // режим мог смениться, пока приложение было свёрнуто (служба, автоопределение)
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.syncModeFromDisk() }
        resumeOwner.lifecycle.addObserver(obs)
        onDispose { resumeOwner.lifecycle.removeObserver(obs) }
    }
    var page by remember { mutableStateOf<Page?>(null) }
    var inSettings by remember { mutableStateOf(false) }
    var inServers by remember { mutableStateOf(false) }

    // Разрешение на уведомления (Android 13+): без него не видно статус сбора и кнопку «Отключить».
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Автоподключение при открытии приложения (если включено в настройках; один раз за запуск экрана)
    var autoDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoDone) {
            autoDone = true
            PhoenixVpnService.autoStart(context, tr("открытие приложения"))
        }
    }

    // Системное окно «Разрешить VPN-подключение» показывается один раз.
    var pending by remember { mutableStateOf<ConfigRow?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val row = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (row != null) PhoenixVpnService.start(context, row.raw, row.displayName) else PhoenixVpnService.start(context)
        }
    }
    /** row == null — «просто подключить» (последний или лучший конфиг). */
    val connect: (ConfigRow?) -> Unit = { row ->
        if (!XrayCore.available) {
            Toast.makeText(context, XrayCore.problem, Toast.LENGTH_LONG).show()
        } else {
            val intent = VpnService.prepare(context)
            if (intent == null) {
                if (row != null) PhoenixVpnService.start(context, row.raw, row.displayName)
                else {
                    // выбор конфига — это рейтинг всех конфигов: не на главном потоке, иначе на слабых телефонах приложение «зависает»
                    val appCtx = context.applicationContext
                    Thread {
                        val has = try { QuickConnect.pick(appCtx) != null } catch (_: Throwable) { false }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (has) PhoenixVpnService.start(appCtx)
                            else Toast.makeText(appCtx, tr("Нет конфигов: нажмите «Собрать сейчас»"), Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            } else {
                pending = row
                consent.launch(intent)
            }
        }
    }

    BackHandler(enabled = inSettings || inServers) {
        if (inServers) inServers = false else if (page != null) page = null else inSettings = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (inServers) tr("Серверы") else if (!inSettings) "Phoenix" else page?.title?.let { tr(it) } ?: tr("Настройки")) },
                navigationIcon = {
                    if (inServers) TextButton(onClick = { inServers = false }) { Text("←") }
                    else if (inSettings) TextButton(onClick = { if (page != null) page = null else inSettings = false }) { Text("←") }
                },
                actions = {
                    if (!inSettings && !inServers) {
                        TextButton(onClick = { inServers = true }) { Text(tr("Серверы")) }
                        TextButton(onClick = { inSettings = true }) { Text("⚙") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (inServers) {
                ServersPage(vm) { row -> inServers = false; connect(row) }
            } else if (!inSettings) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    VpnCard(vm, mode, onConnect = { connect(null) })
                    HomePanel(vm) { inServers = true }
                }
            } else when (page) {
                null -> SettingsMenu(onOpen = { page = it })
                Page.STATE -> StatePage(vm)
                Page.MODE -> ModePage(vm)
                Page.TARGETS -> TargetsPage(vm)
                Page.BYPASS -> VpnTab(vm)
                Page.CONNECTION -> ConnectionPage(vm)
                Page.COLLECT -> SettingsTab(vm)
                Page.FIRMWARE -> FirmwarePage()
                Page.SOURCES -> SourcesTab(vm)
                Page.BACKUP -> BackupPage(vm)
                Page.DIAG -> DiagPage(vm)
            }
        }
    }
}

@Composable
private fun SettingsMenu(onOpen: (Page) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Page.entries.forEach { p ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(p) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr(p.title), style = MaterialTheme.typography.titleMedium)
                    Text(tr(p.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Главная карточка: состояние VPN и крупная кнопка. */
@Composable
fun VpnCard(vm: MainViewModel, mode: Mode, onConnect: () -> Unit) {
    val context = LocalContext.current
    val status by VpnState.status.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val auto by vm.autoMode.collectAsStateWithLifecycle()
    val busy = status.phase == VpnPhase.CONNECTING
    val on = status.phase == VpnPhase.CONNECTED || busy

    // запасные: проверенные живые конфиги на других серверах (тот же сервер с другим протоколом не считается)
    val reserve = remember(rows, checks, status.link) {
        val currentHost = ConfigIdentity.host(status.link)
        Ranking.distinctServers(Ranking.rank(Ranking.candidates(rows, checks, mode = mode)))
            .count { c -> c.host != currentHost && checks[CheckStore.keyOf(c.link)]?.let { !it.dead } == true }
    }

    val best = remember(rows, checks) {
        Ranking.rank(Ranking.candidates(rows, checks, mode = mode)).firstOrNull()?.let { c ->
            val row = rows.firstOrNull { it.raw == c.link }
            val speed = checks[CheckStore.keyOf(c.link)]?.speedText().orEmpty()
            (row?.displayName ?: "") + if (speed.isNotBlank()) " · $speed" else ""
        }
    }

    val killPrefs = remember { context.getSharedPreferences("phoenix", android.content.Context.MODE_PRIVATE) }
    val killTs = remember { killPrefs.getLong("kill_ts", 0L) }
    var killAck by remember { mutableStateOf(killPrefs.getLong("kill_ack", 0L)) }
    if (KillBanner.visible(killTs, killAck, System.currentTimeMillis())) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    KillBanner.text(killPrefs.getInt("kill_code", 13), System.currentTimeMillis() - killTs, OemGuide.hint(Background.vendor())),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { killPrefs.edit().putLong("kill_ack", killTs).apply(); killAck = killTs }) { Text(tr("Понятно")) }
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (status.phase) {
                    VpnPhase.CONNECTING -> tr("Подключаюсь…")
                    VpnPhase.CONNECTED -> tr("VPN включён")
                    VpnPhase.ERROR -> tr("Ошибка VPN")
                    VpnPhase.IDLE -> tr("VPN выключен")
                },
                style = MaterialTheme.typography.titleLarge,
            )
            val detail = when {
                status.phase == VpnPhase.ERROR -> status.error
                status.phase == VpnPhase.IDLE -> best?.takeIf { it.isNotBlank() }?.let { tr("Лучший сейчас: {}", it) }.orEmpty()
                else -> status.title + (if (status.note.isNotBlank()) "\n${status.note}" else "") + tr("\nЗапасных проверено: {}", reserve)
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text(
                tr("Режим: {}", tr(mode.title)) + if (auto) tr(" (определяется сам)") else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val work by vm.busy.collectAsStateWithLifecycle()
            if (work.isNotEmpty()) Text(work, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (on) {
                OutlinedButton(onClick = { SwitchRequest.show.value = true }, enabled = status.phase == VpnPhase.CONNECTED, modifier = Modifier.fillMaxWidth()) { Text(tr("Сменить сервер")) }
                val pinned by VpnState.pinned.collectAsStateWithLifecycle()
                OutlinedButton(onClick = { PhoenixVpnService.smartSwitch(context) }, enabled = status.phase == VpnPhase.CONNECTED, modifier = Modifier.fillMaxWidth()) { Text(tr("Умная смена (под открытое приложение)")) }
                OutlinedButton(onClick = { PhoenixVpnService.veto(context) }, enabled = status.phase == VpnPhase.CONNECTED, modifier = Modifier.fillMaxWidth()) { Text(tr("Открытый сервис не работает на этом сервере")) }
                if (pinned && status.phase == VpnPhase.CONNECTED) {
                    OutlinedButton(onClick = { PhoenixVpnService.unpin(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("📌 Открепить сервер")) }
                }
                OutlinedButton(onClick = { PhoenixVpnService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Отключить")) }
            } else {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text(tr("Подключить")) }
            }
        }
    }
}

// ---------------------------------------------------------------- Конфиги

@Composable
fun HomePanel(vm: MainViewModel, onServers: () -> Unit) {
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val running by RunState.running.collectAsStateWithLifecycle()
    val message by RunState.message.collectAsStateWithLifecycle()
    val rechecking by RecheckState.running.collectAsStateWithLifecycle()
    val recheckProgress by RecheckState.progress.collectAsStateWithLifecycle()

    val alive = remember(rows, checks) {
        rows.count { r -> checks[CheckStore.keyOf(r.raw)]?.let { !it.dead && !it.staleFor(r.raw) } == true }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            text = when {
                running -> "⏳ " + message.ifBlank { tr("Работаю…") }
                rechecking -> "⏳ " + recheckProgress.ifBlank { tr("Перепроверяю серверы…") }
                message.isNotBlank() -> message
                rows.isEmpty() -> tr("Данных ещё нет. Нажмите «Собрать сейчас» (или дождитесь ночного запуска).")
                else -> tr("Последняя сборка загружена.")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            tr("Серверов: {} · проверено живых: {}", rows.size, alive),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.runNow() }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(tr("Собрать сейчас")) }
        OutlinedButton(onClick = onServers, modifier = Modifier.fillMaxWidth()) { Text(tr("Серверы: список, сортировка, фильтры")) }
        OutlinedButton(
            onClick = { Exporter.copy(context, rows.joinToString("\n") { it.raw }, rows.size) },
            enabled = rows.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("Копировать все ({})", rows.size)) }
    }
}

// ---------------------------------------------------------------- Отчёт

@Composable
fun ReportTab(vm: MainViewModel) {
    val context = LocalContext.current
    val report by vm.report.collectAsStateWithLifecycle()
    val reportScope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refresh() }) { Text(tr("Обновить")) }
            OutlinedButton(onClick = {
                reportScope.launch {
                    val ok = try {
                        withContext(Dispatchers.IO) {
                            val text = vm.reportOnlyText()
                            text.isNotEmpty() && Exporter.saveToDownloads(context, "phoenix-report.txt", text)
                        }
                    } catch (_: Throwable) {
                        false
                    }
                    Toast.makeText(
                        context,
                        if (ok) tr("Отчёт сохранён: Загрузки/Phoenix") else tr("Отчёта пока нет"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }) { Text(tr("Сохранить отчёт")) }
        }
        Spacer(Modifier.height(8.dp))
        Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- Источники

@Composable
fun UrlRow(url: String, note: String, action: String?, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (note.isNotBlank()) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Ссылка-источник: адрес, доля рабочих конфигов и выбор приоритета. */
@Composable
fun SourceRow(url: String, note: String, level: Int, onLevel: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(SourcePriority.LOW, SourcePriority.NORMAL, SourcePriority.HIGH).forEach { l ->
                    FilterChip(selected = level == l, onClick = { onLevel(l) }, label = { Text(SourcePriority.title(l)) })
                }
            }
        }
    }
}

@Composable
fun SourcesTab(vm: MainViewModel) {
    val context = LocalContext.current
    val mine by vm.mySources.collectAsStateWithLifecycle()
    val blacklist by vm.blacklist.collectAsStateWithLifecycle()
    val proposals by vm.proposals.collectAsStateWithLifecycle()
    val mode by vm.srcMode.collectAsStateWithLifecycle()
    val activeMode by vm.mode.collectAsStateWithLifecycle()
    val listUrl by vm.listUrl.collectAsStateWithLifecycle()
    val stats by vm.sourceStats.collectAsStateWithLifecycle()
    val prios by vm.sourcePrio.collectAsStateWithLifecycle()
    val rechecking by RecheckState.running.collectAsStateWithLifecycle()
    val recheckText by RecheckState.progress.collectAsStateWithLifecycle()
    var listUrlEdit by remember(mode, listUrl) { mutableStateOf(listUrl) }
    var newSource by remember { mutableStateOf("") }
    var newBlack by remember { mutableStateOf("") }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item { Text(tr("Источники для режима"), style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { vm.setSourcesMode(m) },
                        label = { Text(m.title + if (m == activeMode) " ✓" else "") },
                    )
                }
            }
        }
        item {
            Text(
                tr("У каждого режима свои ссылки, чёрный список и аудит; здесь можно править любой, независимо от того, какой режим включён сейчас (✓). Ночной сбор идёт для каждого режима, у которого есть источники."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(tr("Ссылки: доля рабочих и приоритет"), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                tr("Доля — сколько конфигов ссылки отвечают среди проверенных (свежая проверка за 7 дней или опыт в текущей сети). Приоритет «высокий»: конфиги этой ссылки пробуются раньше других живых, а сама ссылка не попадает в чёрный список по аудиту. «Низкий»: её конфиги идут в самый конец очереди."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Column {
                Button(onClick = { vm.recheckAll() }, enabled = !rechecking) {
                    Text(if (rechecking) tr("Идёт перепроверка…") else tr("Перепроверить все конфиги"))
                }
                OutlinedButton(onClick = { vm.recheckApps() }, enabled = !rechecking) { Text(tr("Перепроверить только приложения")) }
                if (recheckText.isNotBlank()) {
                    Text(recheckText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    tr("Проверяет все конфиги режима заново (сначала TCP, потом настоящая задержка через ядро). Идёт в фоне до 25 минут, можно закрыть экран."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val linkRows = (stats.map { it.url } + mine).distinct()
            .sortedWith(compareByDescending<String> { prios[it] ?: 0 }.thenByDescending { u -> stats.firstOrNull { it.url == u }?.percent ?: -2 })
        if (linkRows.isEmpty()) {
            item {
                Text(
                    tr("Ссылок со статистикой пока нет: она появится после сбора."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(linkRows) { url ->
            val st = stats.firstOrNull { it.url == url }
            SourceRow(
                url = url,
                note = st?.text() ?: tr("ещё не скачивалась"),
                level = prios[url] ?: SourcePriority.NORMAL,
                onLevel = { vm.setSourcePriority(url, it) },
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(tr("Общий список источников — {}", tr(mode.title)), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                if (mode == Mode.WHITELIST)
                    tr("Адрес текстового файла со ссылками на подписки (по одной в строке). Сейчас используется встроенный список; можно указать свой. Пустое поле — вернуть встроенный.")
                else
                    tr("Адрес текстового файла со ссылками на подписки (по одной в строке). Можно оставить пустым и пользоваться только своими ссылками."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = listUrlEdit,
                    onValueChange = { listUrlEdit = it },
                    label = { Text("https://…/sources.txt") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val err = vm.setListUrl(listUrlEdit)
                    toast(err ?: tr("Сохранено"))
                }) { Text(tr("Сохранить")) }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(tr("Свои ссылки в пул ({})", mine.size), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                tr("Добавляются к общему списку источников при каждой сборке."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSource,
                    onValueChange = { newSource = it },
                    label = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val err = vm.addUserSource(newSource)
                    if (err == null) {
                        newSource = ""
                        toast(tr("Добавлено. Появится в следующей сборке"))
                    } else toast(err)
                }) { Text(tr("Добавить")) }
            }
        }
        item {
            val clipContext = LocalContext.current
            OutlinedButton(onClick = {
                // все http(s)-ссылки из буфера обмена (по одной в строке или через пробел) — в свои источники
                val text = try {
                    clipContext.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(clipContext)?.toString().orEmpty()
                } catch (_: Throwable) { "" }
                val found = UserSources.parseSourceList(text)
                var added = 0
                for (u in found) if (vm.addUserSource(u) == null) added++
                toast(if (found.isEmpty()) tr("В буфере нет ссылок https://…") else tr("Добавлено {} из {}. Появятся в следующей сборке", added, found.size))
            }) { Text(tr("Добавить ссылки из буфера обмена")) }
        }
        items(mine) { url -> UrlRow(url, "", tr("Удалить")) { vm.removeUserSource(url) } }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(tr("Предложения аудита ({})", proposals.size), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                tr("Источники, чьи конфиги уже целиком есть в другом. Автоматически добавляются в чёрный список 1-го и 15-го числа (до {} за раз); здесь можно применить сразу.", SourceAudit.MAX_APPLY),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                onClick = {
                    val n = vm.applyProposals()
                    val left = proposals.size - n
                    toast(
                        if (n > 0) tr("Добавлено в чёрный список: {}", n) + if (left > 0) tr(". Остальные автоматически нельзя (причина указана под каждой), их можно добавить кнопкой «В ЧС»") else ""
                        else tr("Автоматически добавлять нечего: причины указаны под предложениями, вручную — кнопка «В ЧС»")
                    )
                },
                enabled = proposals.isNotEmpty(),
            ) { Text(tr("Применить (до {})", SourceAudit.MAX_APPLY)) }
        }
        items(proposals) { p ->
            val skip = SourceAudit.skipReason(p, blacklist)
            UrlRow(
                p.url,
                p.reason + if (skip != null) tr("\n⚠ Автоприменение пропускает: {}. Кнопка «В ЧС» добавит вручную.", skip) else "",
                tr("В ЧС"),
            ) {
                val err = vm.blacklistAdd(p.url)
                toast(err ?: tr("Добавлено в чёрный список"))
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(tr("Чёрный список ({})", blacklist.size), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                tr("Эти источники не скачиваются. Автоматически из списка ничего не удаляется — только вручную."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newBlack,
                    onValueChange = { newBlack = it },
                    label = { Text(tr("Ссылка для чёрного списка")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val err = vm.blacklistAdd(newBlack)
                    if (err == null) {
                        newBlack = ""
                        toast(tr("Добавлено в чёрный список"))
                    } else toast(err)
                }) { Text(tr("В ЧС")) }
            }
        }
        items(blacklist) { e -> UrlRow(e.url, e.reason, tr("Вернуть")) { vm.unblacklist(e.url) } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ---------------------------------------------------------------- Настройки

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Время сбора")) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(tr("Готово")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

@Composable
fun SettingsTab(vm: MainViewModel) {
    val runTime by vm.runTime.collectAsStateWithLifecycle()
    val onlyCharging by vm.onlyCharging.collectAsStateWithLifecycle()
    val kind by vm.scheduleKind.collectAsStateWithLifecycle()
    val hours by vm.intervalHours.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Автосбор конфигов"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == ScheduleKind.DAILY, onClick = { vm.setScheduleKind(ScheduleKind.DAILY) }, label = { Text(tr("В точное время")) })
            FilterChip(selected = kind == ScheduleKind.INTERVAL, onClick = { vm.setScheduleKind(ScheduleKind.INTERVAL) }, label = { Text(tr("Раз в N часов")) })
        }
        Spacer(Modifier.height(8.dp))
        if (kind == ScheduleKind.INTERVAL) {
            Text(tr("Каждые {} ч после последнего удачного сбора", hours), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(3, 6, 12, 24, 48).forEach { h ->
                    FilterChip(selected = hours == h, onClick = { vm.setIntervalHours(h) }, label = { Text(tr("{} ч", h)) })
                }
            }
            Text(
                tr("Не привязан к часам: срок считается от последнего удачного сбора. Телефон был выключен или не было сети — сбор пройдёт при первой возможности, а следующий срок отсчитается от него. Меньше интервал — свежее конфиги, но больше трафика и заряда."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Каждый день в {}", runTime), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("Точное время Android не гарантирует (возможен сдвиг на несколько минут). Если в это время нет сети или телефон выключен — сбор пройдёт при первой возможности."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showDialog = true }) { Text(tr("Изменить")) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Только при зарядке"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("Экономит батарею. Если телефон не заряжается в это время, сбор отложится до подключения зарядки."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = onlyCharging, onCheckedChange = { vm.setOnlyCharging(it) })
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        ReliabilitySection()
    }

    if (showDialog) {
        TimeDialog(
            hour = vm.runHour,
            minute = vm.runMinute,
            onDismiss = { showDialog = false },
            onConfirm = { h, m ->
                vm.setRunTime(h, m)
                showDialog = false
            },
        )
    }
}

@Composable
fun ReliabilitySection() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(Background.status(context)) }

    // Состояние разрешений меняется в системных настройках — обновляем при возврате в приложение.
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = Background.status(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(tr("Надёжность автосбора"), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(tr("Последний запуск: {}", status.lastRunText), style = MaterialTheme.typography.bodyMedium)
    Text(tr("Будильник: {}", status.alarmText), style = MaterialTheme.typography.bodyMedium)
    Text(
        tr("Точные будильники: ") + if (status.exactAlarmAllowed) tr("разрешены") else tr("НЕ разрешены"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        tr("Ограничение батареи: ") + if (status.ignoringBatteryOptimization) tr("снято") else tr("действует"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        tr("Без этих разрешений Android (особенно MIUI/HyperOS) может не запустить сбор ночью, пока приложение закрыто. Тогда сбор пройдёт при первом открытии телефона или приложения."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        tr("Все кнопки разрешений (точные будильники, батарея, автозапуск, данные в фоне) собраны в одном месте: «Настройки → Фоновая работа (прошивка)»."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "MIUI: Настройки → Приложения → Phoenix → Контроль активности → «Нет ограничений»; " +
            "закрепите Phoenix в списке недавних (замок).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
