@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CardDefaults
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSwitchIntent(intent)
        try { ThemeMode.mode.value = AppSettings(this).theme } catch (_: Throwable) { }
        try { LiveActivity.style.value = AppSettings(this).activityStyle } catch (_: Throwable) { }
        setContent {
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides if (Tr.isRtl()) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
            MaterialTheme(colorScheme = if (appDark()) PhoenixTheme.dark else PhoenixTheme.light, shapes = PhoenixTheme.shapes, typography = PhoenixTheme.typography) {
                MainScreen()
                ServerSwitchHost()
            }
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
        if (intent?.getBooleanExtra(SwitchRequest.EXTRA_UPDATE, false) == true) {
            SwitchRequest.openUpdate.value = true
            intent.removeExtra(SwitchRequest.EXTRA_UPDATE)
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

    private val _dnsProvider = MutableStateFlow(settings.dnsProvider)
    val dnsProvider: StateFlow<String> = _dnsProvider.asStateFlow()
    private val _dnsTrialAt = MutableStateFlow(0L)
    /** Когда началась проба нового DNS (0 — пробы нет): экран показывает отсчёт. */
    val dnsTrialAt: StateFlow<Long> = _dnsTrialAt.asStateFlow()

    /**
     * Выбор DNS. Если VPN работает, новый DNS применяется сразу (туннель пересоздаётся) и на [DnsTrial.TRIAL_SEC] секунд идёт проба:
     * не подтвердили — возврат к прежнему. Без VPN просто сохраняется.
     */
    fun setDnsProvider(id: String) {
        val next = DnsProvider.fromId(id).name
        val prev = settings.dnsProvider
        if (next == prev || _dnsTrialAt.value > 0L) return
        settings.dnsProvider = next
        _dnsProvider.value = next
        if (VpnState.status.value.phase != VpnPhase.CONNECTED) { storage.log("DNS: выбран ${DnsProvider.fromId(next).title} (VPN не работает — применится при подключении)"); return }
        val at = System.currentTimeMillis()
        settings.dnsTrialPrev = prev
        settings.dnsTrialAt = at
        _dnsTrialAt.value = at
        storage.log("DNS: проба ${DnsProvider.fromId(next).title}, прежний ${DnsProvider.fromId(prev).title}; ${DnsTrial.TRIAL_SEC} с на подтверждение")
        PhoenixVpnService.reapplyDns(getApplication())
        viewModelScope.launch {
            kotlinx.coroutines.delay(DnsTrial.TRIAL_SEC * 1000L + 500L)
            if (_dnsTrialAt.value == at) rollbackDns("не подтверждён за ${DnsTrial.TRIAL_SEC} с")
        }
    }

    fun confirmDns() {
        if (_dnsTrialAt.value <= 0L) return
        settings.dnsTrialAt = 0L
        _dnsTrialAt.value = 0L
        storage.log("DNS: подтверждён ${DnsProvider.fromId(settings.dnsProvider).title}")
    }

    fun rollbackDns(why: String = "по кнопке") {
        if (_dnsTrialAt.value <= 0L) return
        settings.dnsProvider = settings.dnsTrialPrev
        settings.dnsTrialAt = 0L
        _dnsTrialAt.value = 0L
        _dnsProvider.value = settings.dnsProvider
        storage.log("DNS: возврат к ${DnsProvider.fromId(settings.dnsProvider).title} ($why)")
        PhoenixVpnService.reapplyDns(getApplication())
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
    private val _carryDays = MutableStateFlow(settings.carryDays)
    val carryDays: StateFlow<Int> = _carryDays.asStateFlow()

    fun setCarryDays(d: Int) {
        settings.carryDays = d
        _carryDays.value = settings.carryDays
    }

    /** Состояние проверки обновлений для плашки и страницы «Обновления». */
    data class UpdateUi(val tag: String = "", val url: String = "", val checkedAt: Long = 0L, val busy: Boolean = false, val failed: Boolean = false) {
        fun newer(current: String) = UpdateCheck.isNewer(tag, current)
    }

    private fun updateFromSettings(busy: Boolean = false, failed: Boolean = false) =
        AppSettings(getApplication()).let { UpdateUi(it.updateTag, it.updateUrl, it.updateCheckedAt, busy, failed) }

    private val _update = MutableStateFlow(updateFromSettings())
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    /** [force] — по кнопке; без неё проверяет только если с прошлого раза прошло больше суток. */
    fun checkUpdate(force: Boolean) {
        val app = getApplication<Application>()
        if (_update.value.busy || (!force && !UpdateChecker.due(app))) return
        _update.value = updateFromSettings(busy = true)
        Thread {
            val out = try { UpdateChecker.run(app) } catch (_: Throwable) { null }
            _update.value = updateFromSettings(failed = out == null || !out.reached)
        }.start()
    }

    /** Объявлен выше всех init: иначе reloadSources() из init видит null (падение NPE 17–18.11). */
    private val sourcesLock = kotlinx.coroutines.sync.Mutex()

    private val _install = MutableStateFlow(InstallProgress())
    val install: StateFlow<InstallProgress> = _install.asStateFlow()

    init {
        // отмена или ошибка в окне системного установщика приходит через приёмник — возвращаем кнопку «Скачать и установить»
        viewModelScope.launch { UpdateInstaller.external.collect { _install.value = it } }
    }

    /** «Скачать и установить» по выбору пользователя: скачивание и проверка идут в фоне, окно подтверждения покажет сама система. */
    fun installUpdate() {
        val app = getApplication<Application>()
        val st = _install.value.stage
        if (st == InstallProgress.Stage.DOWNLOADING || st == InstallProgress.Stage.VERIFYING || st == InstallProgress.Stage.INSTALLING) return
        Thread { UpdateInstaller.downloadAndInstall(app) { _install.value = it } }.start()
    }

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
                importText(app, text)
            } catch (e: BackupFormatException) {
                tr("Не удалось: {}", e.message)
            } catch (e: Exception) {
                tr("Не удалось прочитать файл: {}", e.message)
            }
        }
    }

    /** Восстановление из последней автокопии (внутренняя память). */
    fun restoreAutoBackup() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = try {
                val f = AutoBackup.latest(app) ?: throw java.io.IOException(tr("автокопий пока нет"))
                importText(app, f.readText(Charsets.UTF_8))
            } catch (e: BackupFormatException) {
                tr("Не удалось: {}", e.message)
            } catch (e: Exception) {
                tr("Не удалось прочитать файл: {}", e.message)
            }
        }
    }

    private fun importText(app: Application, text: String): String {
        if (text.length > Backup.MAX_IMPORT_CHARS) throw java.io.IOException(tr("файл слишком большой для копии Phoenix"))
        val report = Backup.import(app.filesDir, text)
        try { settings.importMap(report.settings) } catch (e: Exception) { storage.log("BACKUP: настройки из копии не применены: ${e.message}") }
        try { reloadSettingsFlows() } catch (e: Exception) { storage.log("BACKUP: не удалось обновить настройки на экране: ${e.message}") }
        refresh()
        PlanBus.notifyChanged()
        storage.log("BACKUP: импорт — " + report.notes.joinToString("; "))
        return tr("Импорт выполнен:\n") + report.text()
    }

    /** @return сколько сохранённых значений сброшено до рекомендуемых */
    fun resetRecommended(): Int {
        val n = settings.resetRecommended()
        try { reloadSettingsFlows() } catch (e: Exception) { storage.log("SETTINGS: после сброса экран не обновился: ${e.message}") }
        storage.log("SETTINGS: возвращены рекомендуемые значения ($n)")
        return n
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
        _dnsProvider.value = settings.dnsProvider
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
        viewModelScope.launch(Dispatchers.IO) { sourcesLock.withLock { reloadSourcesNow() } }
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
        // setUrl, а не update(t.copy(...)) — иначе правка несёт servers/verifiedAt того Target, который UI открыл
        // раньше, и стирает серверы, найденные фоном с тех пор (находка Фейбла, Targets/reserves #6)
        TargetStore(storage.targetsFile).setUrl(t.id, u)
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

    /** Три крупных числа сверху диагностики: живые конфиги за сутки, всего конфигов, возраст последней перепроверки. */
    fun diagBig(): List<Pair<String, String>> {
        val today = LocalDate.now()
        val checks = CheckStore(storage.checksFile).load()
        val fresh = checks.values.filter { it.date >= today.minusDays(1).toString() }
        val re = settings.lastRecheckAt
        val age = if (re <= 0) "—" else {
            val m = (System.currentTimeMillis() - re) / 60_000
            if (m < 90) tr("{} мин", m) else tr("{} ч", m / 60)
        }
        val total = try { ConfigRepository.load(storage).size } catch (_: Throwable) { 0 }
        return listOf(
            fresh.count { !it.dead }.toString() to tr("живых за сутки"),
            total.toString() to tr("конфигов всего"),
            age to tr("с перепроверки"),
        )
    }

    /** Столбцы (выбранные сервисы) и строки (цели) таблицы «цели × сервисы». */
    fun diagMatrix(): Pair<List<CheckService>, List<MatrixRow>> {
        val checks = CheckStore(storage.checksFile).load()
        val svc = storage.checkSelection().selected()
        val rows = TargetStore(storage.targetsFile).load().map { t ->
            val first = t.servers.firstOrNull()
            val name = if (t.isDirect) tr("напрямую, мимо VPN") else first?.let { LinkParser.parse(it)?.title?.let { s -> urlDecode(s) } }.orEmpty().ifBlank { tr("нет") }
            val res = first?.let { checks[CheckStore.keyOf(it)] }
            MatrixRow(t.label, name, svc.map { s -> res?.status(s.id) ?: ServiceStatus.UNKNOWN })
        }
        return svc to rows
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

    /** Читает файлы источников и считает статистику не в основном потоке; запуски идут по очереди, каждый читает свежие файлы. */
    private fun reloadSources() {
        viewModelScope.launch(Dispatchers.IO) { sourcesLock.withLock { reloadSourcesNow() } }
    }

    private fun reloadSourcesNow() {
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

    /**
     * Аудит повторов по требованию: без скачивания, по сохранённым спискам конфигов каждого источника. Новые предложения заменяют старые;
     * в чёрный список ничего не добавляется — только кнопкой «Применить» или «В ЧС».
     */
    fun runAuditNow(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                sourcesLock.withLock {
                    if (!srcStorage.sourceMembersFile.exists()) return@withLock tr("Списки источников ещё не сохранены: сначала нужен хотя бы один сбор")
                    val members = SourceStats.decode(srcStorage.sourceMembersFile.readText())
                    val bl = Blacklist(srcStorage.blacklistFile)
                    val sets = SourceAudit.fromMembers(members, bl.urls())
                    val keep = KeepList(srcStorage.keepFile).load() +
                        SourcePriorities(srcStorage.sourcePriorityFile).load().filterValues { it >= SourcePriority.HIGH }.keys
                    val r = SourceAudit.analyze(sets)
                    val pending = r.proposals.filter { it.url !in keep && it.url !in bl.urls() }
                    srcStorage.proposalsFile.writeTextAtomic(SourceAudit.serialize(pending))
                    storage.log("AUDIT: ручной, по сохранённым спискам (${sets.size} источников): пересечений ${r.overlaps.size}, предложений ${pending.size}")
                    reloadSourcesNow()
                    tr("Проверено источников: {}. Пересечений: {}, предложений: {}", sets.size, r.overlaps.size, pending.size)
                }
            } catch (e: Exception) {
                tr("Не удалось: {}", e.message)
            }
            withContext(Dispatchers.Main) { onDone(msg) }
        }
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
    MODE("Режим блокировок", "белые/чёрные списки, определение автоматически"),
    TARGETS("Свой сервер для приложений", "запасные серверы: «Только это» или «Весь VPN»"),
    BYPASS("Мимо VPN", "российские приложения и сайты, свои приложения"),
    CONNECTION("Подключение", "автоподключение, IPv6, защита от утечек, плитка в шторке"),
    SERVICES("Проверяемые сервисы", "какие сервисы открывать через серверы (TG, YT…), свои сервисы, проверка по кнопке"),
    COLLECT("Автосбор", "время или интервал сбора, только при зарядке, статус последнего запуска"),
    FIRMWARE("Работа в фоне", "все разрешения для фона в одном месте: батарея, будильники, автозапуск"),
    SOURCES("Источники", "ссылки для белых и чёрных списков, чёрный список, аудит"),
    BACKUP("Экспорт и импорт", "резервная копия настроек, целей, обхода, источников и журнала"),
    UPDATE("Обновления", "проверка новой версии раз в сутки, кнопка «Проверить сейчас»"),
    INTRO("Первые шаги", "повторить настройку: разрешения и первые серверы"),
    COUNTRIES("Страны и серверы", "предпочитаемые и исключённые страны, скрытые серверы"),
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
    // положение в меню переживает поворот экрана, смену темы и размера шрифта (раньше возвращало на главный экран)
    var page by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<Page?, String>(
        save = { it?.name ?: "" },
        restore = { n -> Page.entries.firstOrNull { it.name == n } },
    )) { mutableStateOf<Page?>(null) }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    var inServers by rememberSaveable { mutableStateOf(false) }

    // первый запуск: по экрану на право (вместо диалога про уведомления в первом кадре); возврат — «Ещё → Помощь и данные → Первые шаги»
    val appSettings = remember { AppSettings(context).also { UiAnim.userOn.value = it.animations } }
    var introForce by rememberSaveable { mutableStateOf(false) }
    var onboarding by rememberSaveable { mutableStateOf(Onboarding.steps(appSettings.onboardingSeen, onboardingEnv(context)).isNotEmpty()) }
    // «Требует внимания»: отозванные разрешения и новая версия; пересчитывается при каждом возврате в приложение
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(o)
        onDispose { lifecycleOwner.lifecycle.removeObserver(o) }
    }
    val updateNow by vm.update.collectAsStateWithLifecycle()
    val currentVersion = remember { UpdateChecker.currentVersion(context) }
    val attention = remember(resumeTick, updateNow) { Attention.items(onboardingEnv(context), updateNow.newer(currentVersion)) }
    val introSteps = remember(onboarding, introForce) { if (onboarding) Onboarding.steps(appSettings.onboardingSeen, onboardingEnv(context), introForce) else emptyList() }

    // уведомление об обновлении открывает страницу «Обновления»
    val wantUpdate by SwitchRequest.openUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(wantUpdate) {
        if (wantUpdate) { inServers = false; inSettings = true; page = Page.UPDATE; SwitchRequest.openUpdate.value = false }
    }

    // Новая версия: если за сутки не проверяли (фоновая проверка могла не сработать), смотрим при открытии
    LaunchedEffect(Unit) { vm.checkUpdate(force = false) }

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
        } else UiMessages.post(tr("Нет разрешения VPN → нажмите на круг и разрешите подключение"))
    }
    /** row == null — «просто подключить» (последний или лучший конфиг). */
    val connect: (ConfigRow?) -> Unit = { row ->
        if (!XrayCore.available) {
            UiMessages.post(XrayCore.problem)
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
                            else UiMessages.post(tr("Нет серверов → соберите их: меню ⋮ → «Собрать сейчас»"))
                        }
                    }.start()
                }
            } else {
                pending = row
                consent.launch(intent)
            }
        }
    }

    // три раздела внизу, под большим пальцем: Главная, Серверы, Ещё (в «Ещё» — и «Правила»: цели и обход)
    val tab = when {
        inServers -> 1
        !inSettings -> 0
        else -> 2
    }
    BackHandler(enabled = inSettings || inServers) {
        if (inServers) inServers = false else if (page != null) page = null else inSettings = false
    }

    fun goTab(t: Int) {
        inServers = t == 1
        inSettings = t >= 2
        page = null
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { UiMessages.flow.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    // итоги сбора и перепроверки: прогресс был, а результат пропадал вместе с текстом
    val collectRunning by RunState.running.collectAsStateWithLifecycle()
    val recheckRunning by RecheckState.running.collectAsStateWithLifecycle()
    val rowsNow by vm.rows.collectAsStateWithLifecycle()
    var wasCollecting by remember { mutableStateOf(false) }
    var wasRechecking by remember { mutableStateOf(false) }
    LaunchedEffect(collectRunning) {
        if (wasCollecting && !collectRunning) { kotlinx.coroutines.delay(600); UiMessages.post(tr("Сбор завершён. Серверов: {}", vm.rows.value.size)) }
        wasCollecting = collectRunning
    }
    LaunchedEffect(recheckRunning) {
        if (wasRechecking && !recheckRunning) UiMessages.post(tr("Перепроверка завершена"))
        wasRechecking = recheckRunning
    }

    if (onboarding) OnboardingScreen(introSteps, vm) { onboarding = false; introForce = false } else Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (inServers) tr("Серверы") else if (!inSettings) "Phoenix" else if (page == Page.TARGETS || page == Page.BYPASS) tr("Приложения и сайты") else page?.title?.let { tr(it) } ?: tr("Ещё")) },
                navigationIcon = {
                    // «назад» нужен только внутри подстраницы «Ещё»; разделы переключаются нижней панелью
                    if (inSettings && page != null) IconButton(onClick = { page = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад"))
                    }
                },
            )
        },
        bottomBar = {
            // плавающая капсула: без размытия, радиус 28, отступ 12 от краёв; системный отступ снизу — снаружи капсулы
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                        listOf(
                            Icons.Filled.Home to tr("Главная"),
                            Icons.Filled.LocationOn to tr("Серверы"),
                            Icons.Filled.Menu to tr("Ещё"),
                        ).forEachIndexed { i, (icon, label) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { goTab(i) },
                                icon = { BadgedBox(badge = { if ((i == 1 && (collectRunning || recheckRunning)) || (i == 2 && attention.isNotEmpty())) Badge() }) { Icon(icon, contentDescription = null) } },
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (inServers) {
                ServersPage(vm) { row -> inServers = false; connect(row) }
            } else if (!inSettings) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UpdateBanner(vm) { inSettings = true; page = Page.UPDATE }
                    VpnCard(vm, mode, onConnect = { connect(null) })
                    HomePanel(vm)
                }
            } else when (page) {
                null -> {
                    val targetsNow by vm.targets.collectAsStateWithLifecycle()
                    val mineNow by vm.mySources.collectAsStateWithLifecycle()
                    val kindNow by vm.scheduleKind.collectAsStateWithLifecycle()
                    val hoursNow by vm.intervalHours.collectAsStateWithLifecycle()
                    val animationsNow by UiAnim.userOn.collectAsStateWithLifecycle()
                    val themeNow by ThemeMode.mode.collectAsStateWithLifecycle()
                    SettingsMenu(
                        modeTitle = tr(mode.title),
                        version = remember { UpdateChecker.currentVersion(context) },
                        targetsCount = targetsNow.size,
                        mineCount = mineNow.size,
                        schedule = if (kindNow == ScheduleKind.INTERVAL) tr("каждые {} ч", hoursNow) else tr("каждый день"),
                        attention = attention,
                        newVersion = updateNow.tag,
                        animations = animationsNow,
                        onAnimations = { appSettings.animations = it; UiAnim.userOn.value = it },
                        theme = themeNow,
                        onTheme = { appSettings.theme = it; ThemeMode.mode.value = it },
                        onOpen = { if (it == Page.INTRO) { introForce = true; onboarding = true } else page = it },
                    )
                }
                Page.MODE -> ModePage(vm)
                Page.TARGETS, Page.BYPASS -> Column(Modifier.fillMaxSize()) {
                    // «Правила»: две вкладки — свой сервер для целей и обход VPN
                    TabRow(selectedTabIndex = if (page == Page.BYPASS) 1 else 0) {
                        Tab(selected = page == Page.TARGETS, onClick = { page = Page.TARGETS }, text = { Text(tr("Свой сервер"), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        Tab(selected = page == Page.BYPASS, onClick = { page = Page.BYPASS }, text = { Text(tr("Мимо VPN"), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    if (page == Page.BYPASS) VpnTab(vm, onOpenServices = { page = Page.SERVICES }) else TargetsPage(vm)
                }
                Page.CONNECTION -> ConnectionPage(vm)
                Page.COLLECT -> SettingsTab(vm)
                Page.FIRMWARE -> FirmwarePage()
                Page.SOURCES -> SourcesTab(vm)
                Page.BACKUP -> BackupPage(vm)
                Page.UPDATE -> UpdatePage(vm)
                Page.DIAG -> DiagPage(vm)
                Page.INTRO -> {}
                Page.SERVICES -> ServicesPage(vm)
                Page.COUNTRIES -> CountriesPage(vm)
            }
        }
    }
}

/** Раздел «Ещё»: четыре группы, строки со значком, подписью и текущим значением справа. */
@Composable
private fun SettingsMenu(modeTitle: String, version: String, targetsCount: Int, mineCount: Int, schedule: String, animations: Boolean, onAnimations: (Boolean) -> Unit, theme: Int, onTheme: (Int) -> Unit, attention: List<Attention.Item>, newVersion: String, onOpen: (Page) -> Unit) {
    val groups = listOf(
        "Подключение" to listOf(Page.MODE, Page.CONNECTION, Page.FIRMWARE),
        "Приложения и сайты" to listOf(Page.BYPASS, Page.TARGETS),
        "Серверы" to listOf(Page.COLLECT, Page.SOURCES, Page.SERVICES, Page.COUNTRIES),
        "Помощь и данные" to listOf(Page.DIAG, Page.BACKUP, Page.UPDATE, Page.INTRO),
    )
    fun icon(p: Page) = when (p) {
        Page.MODE -> Icons.Filled.Lock
        Page.CONNECTION -> Icons.Filled.Settings
        Page.TARGETS -> Icons.Filled.Build
        Page.BYPASS -> Icons.Filled.Close
        Page.COLLECT -> Icons.Filled.Refresh
        Page.SERVICES -> Icons.Filled.CheckCircle
        Page.COUNTRIES -> Icons.Filled.LocationOn
        Page.SOURCES -> Icons.Filled.Share
        Page.FIRMWARE -> Icons.Filled.Notifications
        Page.DIAG -> Icons.Filled.Search
        Page.BACKUP -> Icons.AutoMirrored.Filled.Send
        Page.UPDATE -> Icons.Filled.CheckCircle
        Page.INTRO -> Icons.Filled.Home
        else -> Icons.Filled.Menu
    }
    fun value(p: Page): String = when (p) {
        Page.MODE -> modeTitle
        Page.UPDATE -> if (Attention.Item.UPDATE in attention) "● $newVersion" else version
        Page.FIRMWARE -> Attention.backgroundCount(attention).let { if (it > 0) tr("● нужно {}", it) else "" }
        Page.TARGETS -> if (targetsCount > 0) tr("целей: {}", targetsCount) else ""
        Page.SOURCES -> if (mineCount > 0) tr("своих: {}", mineCount) else ""
        Page.COLLECT -> schedule
        Page.COUNTRIES -> (PoolPolicy.prefer.size + PoolPolicy.avoid.size + PoolPolicy.hidden.size).let { if (it > 0) tr("правил: {}", it) else "" }
        else -> ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (attention.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Требует внимания"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    attention.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(if (item == Attention.Item.UPDATE) Page.UPDATE else Page.FIRMWARE) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (item) {
                                    Attention.Item.BACKGROUND -> tr("Нет разрешения на работу в фоне")
                                    Attention.Item.NOTIFICATIONS -> tr("Нет разрешения на уведомления")
                                    Attention.Item.ALARMS -> tr("Нет точных будильников: сбор сдвигается")
                                    Attention.Item.UPDATE -> tr("Есть новая версия {}", newVersion)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
        groups.forEach { (group, pages) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(group), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    pages.forEachIndexed { i, p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon(p), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr(p.title), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tr(p.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val v = value(p)
                            if (v.isNotEmpty()) Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Внешний вид"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Тема"), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ThemeChoice.SYSTEM to tr("Как в системе"), ThemeChoice.LIGHT to tr("Светлая"), ThemeChoice.DARK to tr("Тёмная")).forEach { (v, label) ->
                            FilterChip(selected = theme == v, onClick = { onTheme(v) }, label = { Text(label) })
                        }
                    }
                }
            }
            val langCtx = LocalContext.current
            var langDialog by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().clickable {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // Android 13+: язык приложения выбирается в системных настройках (locales_config.xml уже есть)
                    try {
                        langCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:" + langCtx.packageName)))
                    } catch (_: Throwable) { langDialog = true }
                } else langDialog = true
            }, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("Язык"), style = MaterialTheme.typography.bodyLarge)
                        Text(LanguageChoice.nameOf(Tr.currentLang()) ?: Tr.currentLang(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (langDialog) {
                val langSet = remember { AppSettings(langCtx) }
                AlertDialog(
                    onDismissRequest = { langDialog = false },
                    title = { Text(tr("Язык")) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                langSet.language = ""; Tr.override = null; Tr.reset(); langDialog = false
                                (langCtx as? android.app.Activity)?.recreate()
                            }) { Text(tr("Как в системе")) }
                            LanguageChoice.items.forEach { (code, name) ->
                                TextButton(onClick = {
                                    langSet.language = code; Tr.override = code; Tr.reset(); langDialog = false
                                    (langCtx as? android.app.Activity)?.recreate()
                                }) { Text(name) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { langDialog = false }) { Text(tr("Закрыть")) } },
                )
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                var speedNow by remember { mutableStateOf(AppSettings(langCtx).recheckSpeed) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tr("Скорость перепроверки"), style = MaterialTheme.typography.bodyLarge)
                    Text(tr("Быстрая запускает больше проверок сразу: быстрее, но сильнее грузит роутер и батарею"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(RecheckSpeed.GENTLE to tr("Щадящая"), RecheckSpeed.NORMAL to tr("Обычная"), RecheckSpeed.FAST to tr("Быстрая")).forEach { (v, label) ->
                            FilterChip(selected = speedNow == v, onClick = { speedNow = v; AppSettings(langCtx).recheckSpeed = v }, label = { Text(label) })
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                val styleNow by LiveActivity.style.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tr("Главный экран: чем занят"), style = MaterialTheme.typography.bodyLarge)
                    Text(tr("что показывать над списком серверов: поиск, сборка, перепроверка"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ActivityStyle.OFF to tr("Выкл"), ActivityStyle.SHORT to tr("Кратко"), ActivityStyle.DETAILED to tr("Подробно"), ActivityStyle.LOG to tr("С журналом")).forEach { (v, label) ->
                            FilterChip(selected = styleNow == v, onClick = { LiveActivity.style.value = v; AppSettings(langCtx).activityStyle = v }, label = { Text(label) })
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onAnimations(!animations) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("Анимации"), style = MaterialTheme.typography.bodyLarge)
                        Text(tr("свечение круга; системное «Убрать анимацию» их тоже выключает"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = animations, onCheckedChange = { onAnimations(it) })
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
    val services by vm.selectedServices.collectAsStateWithLifecycle()
    // сразу после нажатия круг показывает «Подключаюсь…», не дожидаясь службы; ответа нет 15 с или пришло сообщение об отказе — сброс
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(status.phase) { if (status.phase != VpnPhase.IDLE) waiting = false }
    LaunchedEffect(waiting) {
        if (waiting) {
            kotlinx.coroutines.delay(15_000)
            if (waiting) { waiting = false; UiMessages.post(tr("Подключение не началось → нажмите на круг ещё раз")) }
        }
    }
    LaunchedEffect(Unit) { UiMessages.flow.collect { waiting = false } }
    val shownPhase = if (waiting && status.phase == VpnPhase.IDLE) VpnPhase.CONNECTING else status.phase
    val busy = shownPhase == VpnPhase.CONNECTING
    val on = status.phase == VpnPhase.CONNECTED || busy

    // рейтинг всего пула считается в фоне: в главном потоке он подвешивал экран при каждом обновлении проверок
    val home by produceState(HomeStats.EMPTY, rows, checks, status.link, mode) {
        value = withContext(Dispatchers.Default) { HomeStats.build(rows, checks, status.link, mode) }
    }
    val reserve = home.reserve
    val best = home.best

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
    val isDark = appDark()
    val scheme = MaterialTheme.colorScheme
    val phase = shownPhase
    val (okBg, okFg) = PhoenixTheme.safe(isDark)
    // уточнения от службы: поиск сервера и «нет сети» — не «Ошибка» и не «Защищено»
    val sub = if (waiting && status.phase == VpnPhase.IDLE) VpnSub.NONE else status.sub
    val searching = sub == VpnSub.SEARCHING && phase != VpnPhase.IDLE && phase != VpnPhase.ERROR
    val offline = sub == VpnSub.NO_NETWORK && phase != VpnPhase.IDLE && phase != VpnPhase.ERROR
    val (warnBgS, warnFgS) = PhoenixTheme.warn(isDark)
    val warnTone = PhoenixTheme.amber(isDark)
    // «уголёк»: цвет круга и кольца показывает состояние; зелёный — только «защищено», красный — только ошибка
    val circleTarget = when {
        offline -> scheme.surfaceContainer
        searching && phase == VpnPhase.CONNECTED -> okBg
        else -> when (phase) {
        VpnPhase.CONNECTED -> okBg
        VpnPhase.ERROR -> scheme.errorContainer
        else -> scheme.surfaceContainer
        }
    }
    val ringTarget = when {
        offline || searching -> warnTone
        else -> when (phase) {
        VpnPhase.CONNECTED -> okFg
        VpnPhase.ERROR -> scheme.error
        VpnPhase.CONNECTING -> scheme.primary
        VpnPhase.IDLE -> scheme.outline
        }
    }
    // анимации — только цвет круга и свечение; выключены переключателем «Анимации» и системным «Убрать анимацию»
    val animOn by UiAnim.userOn.collectAsStateWithLifecycle()
    val systemScale = remember { try { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Throwable) { 1f } }
    val animate = AnimPolicy.enabled(animOn, systemScale)
    val circleBg by animateColorAsState(circleTarget, animationSpec = tween(if (animate) 300 else 0), label = "circleBg")
    val ringColor by animateColorAsState(ringTarget, animationSpec = tween(if (animate) 300 else 0), label = "ringColor")
    val title = when {
        offline -> tr("Нет сети")
        searching -> tr("Ищу сервер")
        else -> when (phase) {
        VpnPhase.CONNECTING -> tr("Подключаюсь…")
        VpnPhase.CONNECTED -> tr("Защищено")
        VpnPhase.ERROR -> tr("Ошибка VPN")
        VpnPhase.IDLE -> tr("Не защищено")
        }
    }
    val detail = when {
        phase == VpnPhase.ERROR -> status.error + (ErrorHint.key(status.error)?.let { "\n" + tr(it) } ?: "")
        phase == VpnPhase.IDLE -> tr("Нажмите, чтобы подключить") + (best?.takeIf { it.isNotBlank() }?.let { "\n" + tr("Лучший сейчас: {}", it) } ?: "")
        offline -> tr("Подключусь, когда сеть появится")
        phase == VpnPhase.CONNECTED -> status.note
        else -> status.title + (if (status.note.isNotBlank()) "\n${status.note}" else "")
    }
    val work by vm.busy.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Box(contentAlignment = Alignment.Center) {
        if (phase == VpnPhase.CONNECTED && sub == VpnSub.NONE && animate) {
            val glow = rememberInfiniteTransition(label = "glow")
            val k by glow.animateFloat(0f, 1f, infiniteRepeatable(tween(AnimPolicy.GLOW_PERIOD_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Restart), label = "glowK")
            Box(
                Modifier.size(168.dp)
                    .graphicsLayer { val sc = AnimPolicy.glowScale(k); scaleX = sc; scaleY = sc; alpha = AnimPolicy.glowAlpha(k) }
                    .border(3.dp, okFg, CircleShape),
            )
        } else if (phase == VpnPhase.CONNECTED && sub == VpnSub.NONE) {
            // без анимаций: неподвижное второе кольцо вместо «дыхания»
            Box(Modifier.size(180.dp).border(2.dp, okFg.copy(alpha = 0.35f), CircleShape))
        }
        Box(
            Modifier.size(168.dp).clip(CircleShape).background(circleBg)
                .semantics { contentDescription = title }
                .clickable(enabled = phase != VpnPhase.CONNECTING) { if (on) PhoenixVpnService.stop(context) else { waiting = true; onConnect() } },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(color = ringColor, radius = size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(width = 3.dp.toPx())) }
            when {
                offline -> Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = warnTone)
                searching -> Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(64.dp), tint = warnTone)
                else -> when (phase) {
                VpnPhase.CONNECTED -> Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(72.dp), tint = okFg)
                VpnPhase.ERROR -> Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = scheme.error)
                else -> PowerGlyph(if (phase == VpnPhase.CONNECTING) scheme.primary else scheme.onSurfaceVariant)
                }
            }
            if (phase == VpnPhase.CONNECTING) {
                if (animate) CircularProgressIndicator(Modifier.fillMaxSize(), color = scheme.primary, strokeWidth = 4.dp)
                else Canvas(Modifier.fillMaxSize()) {
                    // без анимаций: неподвижная дуга 270°
                    val sw = 4.dp.toPx()
                    drawArc(scheme.primary, startAngle = -90f, sweepAngle = 270f, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), style = Stroke(width = sw))
                }
            }
        }
        }
        Spacer(Modifier.height(16.dp))
        val titleTarget = when {
            offline || searching -> warnTone
            else -> when (phase) {
            VpnPhase.CONNECTED -> okFg
            VpnPhase.CONNECTING -> scheme.primary
            VpnPhase.ERROR -> scheme.error
            VpnPhase.IDLE -> scheme.onSurface
            }
        }
        val titleColor by animateColorAsState(titleTarget, animationSpec = tween(if (animate) 300 else 0), label = "titleColor")
        Text(title, style = MaterialTheme.typography.headlineMedium, color = titleColor)
        if (!auto) Text(tr(mode.title), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        if (work.isNotEmpty()) Text(work, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (on) {
            Spacer(Modifier.height(16.dp))
            val pinned by VpnState.pinned.collectAsStateWithLifecycle()
            // карточка текущего сервера: нажатие открывает окно смены (раньше — отдельная кнопка и «⋮»)
            Card(
                Modifier.fillMaxWidth().clickable(enabled = phase == VpnPhase.CONNECTED) { SwitchRequest.show.value = true },
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status.title.ifBlank { "Phoenix" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (pinned) tr("📌 закреплён · сменить") else tr("Сменить сервер"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    if (pinned && phase == VpnPhase.CONNECTED) TextButton(onClick = { PhoenixVpnService.unpin(context) }) { Text(tr("Открепить")) }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
            }
            if (phase == VpnPhase.CONNECTED) {
                Spacer(Modifier.height(12.dp))
                ServiceStrip(services, checks[CheckStore.keyOf(status.link)])
            }
            Spacer(Modifier.height(12.dp))
            // главное действие в плохой момент: цвет «поиск/предупреждение», видно, что кнопка живая
            val (warnBg, warnFg) = PhoenixTheme.warn(isDark)
            FilledTonalButton(
                onClick = { PhoenixVpnService.fix(context) },
                enabled = phase == VpnPhase.CONNECTED && !searching && !offline,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = warnBg, contentColor = warnFg,
                    disabledContainerColor = scheme.surfaceContainerHigh, disabledContentColor = scheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Не работает"))
            }
        }
    }
}

/** Значок питания (кружок с чёрточкой), нарисованный на холсте: без картинок и зависимостей. */
@Composable
private fun PowerGlyph(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(64.dp)) {
        val w = 6.dp.toPx()
        val s = size.minDimension
        val side = s - 2 * w - s * 0.1f
        drawArc(
            color = color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset((s - side) / 2, s - side - w),
            size = androidx.compose.ui.geometry.Size(side, side),
            style = Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawLine(color, androidx.compose.ui.geometry.Offset(s / 2, w), androidx.compose.ui.geometry.Offset(s / 2, s * 0.5f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- Конфиги

/** Строки подробностей перепроверки для главной: сколько прошло, скорость, итоги по видам отказа, параметры. */
fun activityDetailLines(d: RecheckDetail): List<String> {
    val out = ArrayList<String>()
    val rate = d.perMinute()
    val eta = d.eta()
    val etaText = when {
        eta < 0 -> ""
        eta < 60 -> " · " + tr("осталось ~{} с", eta)
        eta < 3600 -> " · " + tr("осталось ~{} мин", (eta + 30) / 60)
        else -> " · " + tr("осталось ~{} ч {} мин", eta / 3600, (eta % 3600) / 60)
    }
    out += tr("Прошло {} · {} серверов в минуту", ActivityText.clock(d.elapsedMs), if (rate < 0) "—" else rate.toString()) + etaText
    out += tr("Живых {} · порт закрыт {} · порт открыт, но интернета нет {} · пропущено по адресу {}", d.alive, d.tcpFailed, d.portOpenNoInternet(), d.skipped)
    val speedName = when (d.speed) { RecheckSpeed.GENTLE -> tr("Щадящая"); RecheckSpeed.FAST -> tr("Быстрая"); else -> tr("Обычная") }
    out += tr("Скорость «{}»: портов одновременно {}, проб {}, уровень сети {}", speedName, d.tcpParallel, d.probeParallel, d.level) + if (d.foreign) " · " + tr("конфиги другого режима") else ""
    if (d.quarantined > 0) out += tr("Не проверяю: {} (недавно мёртвые, в этой сети подряд не работали)", d.quarantined)
    return out
}

fun targetsDetailLine(d: TargetsDetail): String =
    tr("Цели {}/{}: найдено {}, без сервера {}, мимо VPN {}, на паузе {} · проверено серверов {}", d.index, d.total, d.found, d.none, d.direct, d.paused, d.tested)

@Composable
fun HomePanel(vm: MainViewModel) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val running by RunState.running.collectAsStateWithLifecycle()
    val message by RunState.message.collectAsStateWithLifecycle()
    val rechecking by RecheckState.running.collectAsStateWithLifecycle()
    val recheckProgress by RecheckState.progress.collectAsStateWithLifecycle()
    val targetsText by LiveActivity.targets.collectAsStateWithLifecycle()
    val recheckDetail by RecheckState.detail.collectAsStateWithLifecycle()
    val targetsDetail by LiveActivity.targetsDetail.collectAsStateWithLifecycle()
    val stopAt by StopRequest.at.collectAsStateWithLifecycle()
    var detailsOpen by rememberSaveable { mutableStateOf(true) }
    val vpnStatus by VpnState.status.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val style by LiveActivity.style.collectAsStateWithLifecycle()
    val homeCtx = LocalContext.current
    var logTail by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(style) {
        if (style == ActivityStyle.LOG) while (true) {
            logTail = try { withContext(Dispatchers.IO) { LogTrim.tailLines(Storage(homeCtx).logFile, 16_384).takeLast(4) } } catch (_: Throwable) { emptyList() }
            kotlinx.coroutines.delay(2_000)
        } else logTail = emptyList()
    }
    // что делает Phoenix сейчас: сборка, перепроверка, подбор для целей, поиск сервера; все активные дела строками, без дел — одна спокойная строка
    val doing = buildList {
        if (running) add(message.ifBlank { tr("Собираю конфиги…") })
        if (rechecking) add(recheckProgress.ifBlank { tr("Перепроверяю серверы…") })
        if (targetsText.isNotEmpty()) add(targetsText)
        if (vpnStatus.sub == VpnSub.SEARCHING) add(vpnStatus.note.ifBlank { tr("Ищу рабочий сервер…") })
    }
    // на главной только то, что идёт прямо сейчас, и пустое состояние; число серверов и «Собрать сейчас» — в «Серверах» и в меню «⋮» сверху
    val busyText = when {
        running -> message.ifBlank { tr("Работаю…") }
        rechecking -> recheckProgress.ifBlank { tr("Перепроверяю серверы…") }
        else -> ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (style == ActivityStyle.SHORT && doing.isNotEmpty() && rows.isNotEmpty()) {
            Text("⏳ " + doing.first() + (if (doing.size > 1) "  (+${doing.size - 1})" else ""), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (style >= ActivityStyle.DETAILED && doing.isNotEmpty() && rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clickable { detailsOpen = !detailsOpen }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                doing.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (detailsOpen) {
                    recheckDetail?.let { d -> activityDetailLines(d).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
                    targetsDetail?.let { d -> Text(targetsDetailLine(d), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                if (recheckDetail != null || targetsDetail != null) Text(if (detailsOpen) tr("нажмите, чтобы свернуть подробности") else tr("нажмите, чтобы показать подробности"), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = scheme.primary, trackColor = scheme.surfaceContainerHigh)
        } else if (style >= ActivityStyle.DETAILED && doing.isEmpty() && rows.isNotEmpty()) {
            Text(tr("Сейчас ничего не ищу и не проверяю"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        // работа идёт, а результаты уже есть (полчаса ждать не нужно): остановить и оставить то, что проверено
        LaunchedEffect(doing.isEmpty()) { if (doing.isEmpty()) StopRequest.at.value = 0L }
        if (doing.isNotEmpty() && rows.isNotEmpty() && style != ActivityStyle.OFF) {
            val stopping = stopAt > 0
            OutlinedButton(onClick = { StopRequest.request() }, enabled = !stopping, modifier = Modifier.fillMaxWidth()) {
                Text(if (stopping) tr("Останавливаю, сохраняю результаты…") else tr("Остановить и сохранить"))
            }
        }
        if (style == ActivityStyle.LOG && rows.isNotEmpty()) {
            logTail.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (busyText.isNotEmpty() && rows.isEmpty()) {
            Text(busyText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = scheme.primary, trackColor = scheme.surfaceContainerHigh)
        } else if (rows.isEmpty()) {
            Text(tr("Серверов пока нет"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            FilledTonalButton(onClick = { vm.runNow() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(tr("Собрать")) }
        }
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
                var full by rememberSaveable(url) { mutableStateOf(false) }
                Text(if (full) url else ShortUrl.of(url), style = MaterialTheme.typography.bodySmall, maxLines = if (full) 4 else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { full = !full })
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
            var full by rememberSaveable(url) { mutableStateOf(false) }
            Text(if (full) url else ShortUrl.of(url), style = MaterialTheme.typography.bodySmall, maxLines = if (full) 4 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { full = !full })
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(SourcePriority.RESERVE, SourcePriority.LOW, SourcePriority.NORMAL, SourcePriority.HIGH).forEach { l ->
                    FilterChip(selected = level == l, onClick = { onLevel(l) }, label = { Text(tr(SourcePriority.title(l))) })
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

    val statByUrl = remember(stats) { stats.associateBy { it.url } }
    val linkRows = remember(statByUrl, mine, prios) {
        (statByUrl.keys + mine).distinct()
            .sortedWith(compareByDescending<String> { prios[it] ?: 0 }.thenByDescending { u -> statByUrl[u]?.percent ?: -2 })
    }

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
                        label = { Text(tr(m.title) + if (m == activeMode) " ✓" else "") },
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
        item { Spacer(Modifier.height(16.dp)) }
        item { Text(tr("Ссылки: доля рабочих и приоритет"), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                tr("Доля — сколько конфигов ссылки отвечают среди проверенных (свежая проверка за 7 дней или опыт в текущей сети). Приоритет «высокий»: конфиги этой ссылки пробуются раньше других живых, а сама ссылка не попадает в чёрный список по аудиту. «Низкий»: её конфиги идут в самый конец очереди."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (linkRows.isEmpty()) {
            item {
                Text(
                    tr("Ссылок со статистикой пока нет: она появится после сбора."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(linkRows, key = { "stat|$it" }) { url ->
            val st = statByUrl[url]
            SourceRow(
                url = url,
                note = st?.text() ?: tr("ещё не скачивалась"),
                level = prios[url] ?: SourcePriority.NORMAL,
                onLevel = { vm.setSourcePriority(url, it) },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
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
        item { Spacer(Modifier.height(16.dp)) }
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
        items(mine.distinct(), key = { "mine|$it" }) { url -> UrlRow(url, "", tr("Удалить")) { vm.removeUserSource(url) } }

        item { Spacer(Modifier.height(16.dp)) }
        item { Text(tr("Предложения аудита ({})", proposals.size), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                tr("Источники, чьи конфиги уже целиком есть в другом. Автоматически добавляются в чёрный список 1-го и 15-го числа (до {} за раз); здесь можно применить сразу.", SourceAudit.MAX_APPLY),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedButton(onClick = { vm.runAuditNow { toast(it) } }) { Text(tr("Проверить повторы сейчас")) }
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

        item { Spacer(Modifier.height(16.dp)) }
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
        items(blacklist.distinctBy { it.url }, key = { "black|${it.url}" }) { e -> UrlRow(e.url, e.reason, tr("Вернуть")) { vm.unblacklist(e.url) } }
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
    val carryDays by vm.carryDays.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Автосбор конфигов"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == ScheduleKind.DAILY, onClick = { vm.setScheduleKind(ScheduleKind.DAILY) }, label = { Text(tr("В точное время")) })
            FilterChip(selected = kind == ScheduleKind.INTERVAL, onClick = { vm.setScheduleKind(ScheduleKind.INTERVAL) }, label = { Text(tr("Раз в N часов")) })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (carryDays == 0) tr("Рабочие конфиги прошлого сбора не переносятся") else tr("Рабочие конфиги прошлого сбора остаются в списке ещё {} дн., если источник их не отдал", carryDays),
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            listOf(0, 1, 3, 7, 14).forEach { d ->
                FilterChip(selected = carryDays == d, onClick = { vm.setCarryDays(d) }, label = { Text(if (d == 0) tr("Выкл") else tr("{} дн.", d)) })
            }
        }
        Text(
            tr("Если сбор скачал не все источники, конфиги, которые раньше работали, не пропадают, а смешиваются с новыми (повторы убираются). Срок считается с последнего раза, когда источник их отдавал."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (kind == ScheduleKind.INTERVAL) {
            Text(tr("Каждые {} ч после последнего удачного сбора", hours), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
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
        val bgCtx = LocalContext.current
        val bgSet = remember { AppSettings(bgCtx) }
        var wifiOnly by remember { mutableStateOf(bgSet.wifiOnly) }
        Text(tr("Сеть для сбора и перепроверки"), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(false to tr("Любая"), true to tr("Только Wi-Fi")).forEach { (v, label) ->
                FilterChip(selected = wifiOnly == v, onClick = { wifiOnly = v; bgSet.wifiOnly = v; Scheduler.reschedule(bgCtx) }, label = { Text(label) })
            }
        }
        Text(
            tr("Если удачного сбора не было больше трёх суток, он пройдёт в любой сети. Ручной запуск и поиск сервера при обрыве от этого не зависят."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        var budget by remember { mutableStateOf(bgSet.mobileBudgetMb) }
        Text(tr("Лимит проверок по мобильной сети в сутки"), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            BackgroundBudget.CHOICES.forEach { mb ->
                FilterChip(selected = budget == mb, onClick = { budget = mb; bgSet.mobileBudgetMb = mb }, label = { Text(if (mb == 0) tr("Без лимита") else tr("{} МБ", mb)) })
            }
        }
        Text(
            tr("Касается автоматической перепроверки и замеров скорости. Поиск сервера при обрыве не ограничивается никогда."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.height(4.dp))
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

    Spacer(Modifier.height(4.dp))
    Spacer(Modifier.height(12.dp))
    Text(tr("Надёжность автосбора"), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    val dark = appDark()
    val level = BackgroundHealth.level(status.ignoringBatteryOptimization, status.exactAlarmAllowed, status.alarmSet)
    val levelColor = when (level) {
        HealthLevel.OK -> PhoenixTheme.ok(dark)
        HealthLevel.WARN -> PhoenixTheme.amber(dark)
        HealthLevel.BAD -> MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(levelColor, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when (level) {
                        HealthLevel.OK -> tr("Автосбор сработает вовремя")
                        HealthLevel.WARN -> tr("Автосбор может не сработать")
                        HealthLevel.BAD -> tr("Автосбор не запланирован")
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(tr("Следующий сбор: {}", status.alarmText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(tr("Последний запуск: {}", status.lastRunText), style = MaterialTheme.typography.bodyMedium)
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
}


/** Плашка «Доступна новая версия» на главном экране; показывается, только если на GitHub версия новее установленной. */
@Composable
private fun UpdateBanner(vm: MainViewModel, onMore: () -> Unit) {
    val context = LocalContext.current
    val u by vm.update.collectAsStateWithLifecycle()
    val current = remember { UpdateChecker.currentVersion(context) }
    if (!u.newer(current)) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Доступна новая версия {}", u.tag), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, u.url.ifEmpty { UpdateCheck.PAGE_URL }) }) { Text(tr("Открыть страницу загрузки")) }
                TextButton(onClick = onMore) { Text(tr("Обновления")) }
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** Страница «Обновления»: версия, когда проверяли, кнопка принудительной проверки. */
@Composable
private fun UpdatePage(vm: MainViewModel) {
    val context = LocalContext.current
    val u by vm.update.collectAsStateWithLifecycle()
    val current = remember { UpdateChecker.currentVersion(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Текущая версия: {}", current), style = MaterialTheme.typography.titleMedium)
        Text(
            if (u.checkedAt == 0L) tr("Последняя проверка: {}", tr("ещё не проверялось"))
            else tr("Последняя проверка: {}", RelTime.format(u.checkedAt, System.currentTimeMillis())),
            style = MaterialTheme.typography.bodyLarge,
        )
        when {
            u.busy -> Text(tr("Проверяю…"), style = MaterialTheme.typography.bodyLarge)
            u.failed -> Text(tr("Не удалось проверить: нет связи с GitHub"), color = MaterialTheme.colorScheme.error)
            u.newer(current) -> Text(tr("Новая версия: {}", u.tag), style = MaterialTheme.typography.titleMedium)
            u.checkedAt != 0L -> Text(tr("Установлена последняя версия"), style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !u.busy, onClick = { vm.checkUpdate(force = true) }) { Text(tr("Проверить сейчас")) }
            if (u.newer(current)) OutlinedButton(onClick = { openUrl(context, u.url.ifEmpty { UpdateCheck.PAGE_URL }) }) { Text(tr("Открыть страницу загрузки")) }
        }
        if (u.newer(current)) InstallControls(vm)
        Text(
            tr("Приложение раз в сутки смотрит страницу релизов на GitHub; при выходе новой версии придёт отдельное уведомление. Установить её можно кнопкой «Скачать и установить» или вручную: скачайте APK на странице релиза."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** Кнопка «Скачать и установить» с ходом загрузки; появляется, только если в релизе есть файл APK. */
@Composable
private fun InstallControls(vm: MainViewModel) {
    val context = LocalContext.current
    val p by vm.install.collectAsStateWithLifecycle()
    val hasApk = remember { AppSettings(context).updateApkUrl.isNotEmpty() }
    if (!hasApk) return
    val busy = p.stage == InstallProgress.Stage.DOWNLOADING || p.stage == InstallProgress.Stage.VERIFYING || p.stage == InstallProgress.Stage.INSTALLING
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = !busy, onClick = { vm.installUpdate() }) { Text(tr("Скачать и установить")) }
        when (p.stage) {
            InstallProgress.Stage.DOWNLOADING -> Text(tr("Скачиваю… {}%", p.percent), style = MaterialTheme.typography.bodyMedium)
            InstallProgress.Stage.VERIFYING -> Text(tr("Проверяю файл…"), style = MaterialTheme.typography.bodyMedium)
            InstallProgress.Stage.INSTALLING -> Text(tr("Передаю на установку: подтвердите в системном окне"), style = MaterialTheme.typography.bodyMedium)
            InstallProgress.Stage.NEED_PERMISSION -> {
                Text(tr("Разрешите Phoenix устанавливать приложения, затем нажмите «Скачать и установить» ещё раз"), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { UpdateInstaller.openPermissionSettings(context) }) { Text(tr("Открыть разрешение")) }
            }
            InstallProgress.Stage.FAILED -> Text(tr("Не удалось: {}", p.error), color = MaterialTheme.colorScheme.error)
            InstallProgress.Stage.IDLE -> {}
        }
    }
}
