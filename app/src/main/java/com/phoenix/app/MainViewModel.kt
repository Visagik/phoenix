package com.phoenix.app

// ViewModel главного экрана (вынесено из MainActivity.kt без изменения поведения).

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
import androidx.compose.material.icons.filled.Star
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
    data class UpdateUi(val tag: String = "", val url: String = "", val checkedAt: Long = 0L, val busy: Boolean = false, val failed: Boolean = false, val reinstall: Boolean = false) {
        fun newer(current: String) = UpdateCheck.isNewer(tag, current)
    }

    private fun updateFromSettings(busy: Boolean = false, failed: Boolean = false) =
        AppSettings(getApplication()).let { UpdateUi(it.updateTag, it.updateUrl, it.updateCheckedAt, busy, failed, it.updateReinstall) }

    private val _update = MutableStateFlow(updateFromSettings())
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    /** [force] — по кнопке; без неё проверяет только если с прошлого раза прошло больше суток. */
    fun checkUpdate(force: Boolean) {
        val app = getApplication<Application>()
        if (LiteProfile.ON || _update.value.busy || (!force && !UpdateChecker.due(app))) return
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
        // тип блокировок определяем сразу при запуске приложения, а не после включения VPN: до этого всё шло в режиме «по умолчанию»
        viewModelScope.launch { kotlinx.coroutines.delay(2_000); try { ModeFirst.ensure(app) } catch (_: Throwable) {} }
    }

    /** «Скачать и установить» по выбору пользователя: скачивание и проверка идут в фоне, окно подтверждения покажет сама система. */
    fun installUpdate() {
        val app = getApplication<Application>()
        val st = _install.value.stage
        if (st == InstallProgress.Stage.DOWNLOADING || st == InstallProgress.Stage.VERIFYING || st == InstallProgress.Stage.INSTALLING) return
        // при смене ключа приложение придётся удалить — без сохранённой копии не скачиваем
        if (settings.updateReinstall && _reinstallBackup.value.let { it.isEmpty() || it == "-" }) return
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
    fun recheckAll() { StopRequest.clear(); Recheck.enqueue(getApplication<Application>(), storage.mode) }

    /** Перепроверить только приложения у рабочих конфигов (быстро, без полной перепроверки). */
    fun oddsLookup(): (String, String) -> Int? = CheckStore(storage.checksFile).odds()

    fun exitLookup(): (String) -> String? = ExitStore(storage.exitFile).load().let { m -> { key -> m[key]?.cc } }

    fun recheckApps() { StopRequest.clear(); Recheck.enqueueServices(getApplication<Application>(), storage.mode) }

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
                val text = Backup.export(app.filesDir, settings.exportMap(), stamp, lists = settings.exportLists())
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw java.io.IOException(tr("не удалось открыть файл"))
                tr("Копия сохранена ({} строк)", text.lines().size)
            } catch (e: Exception) {
                tr("Не удалось сохранить: {}", e.message)
            }
        }
    }

    private val _reinstallBackup = MutableStateFlow("")
    /** Имя файла копии, сохранённой в Загрузки перед удалением приложения; «-» — не удалось; пусто — ещё не делали. */
    val reinstallBackup: StateFlow<String> = _reinstallBackup.asStateFlow()

    /** Копия в Загрузки/Phoenix: внутренняя память пропадёт вместе с приложением, а эта останется. */
    fun saveBackupBeforeReinstall() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val name = "phoenix-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt"
            val ok = try { Exporter.saveToDownloads(app, name, Backup.export(app.filesDir, settings.exportMap(), java.time.OffsetDateTime.now().toString(), lists = settings.exportLists())) } catch (_: Exception) { false }
            _reinstallBackup.value = if (ok) name else "-"
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
        val report = Backup.import(app.filesDir, text, LiteProfile.skipModes())
        try { settings.importMap(report.settings) } catch (e: Exception) { storage.log("BACKUP: настройки из копии не применены: ${e.message}") }
        try {
            settings.importLists(report.lists)
            if (report.lists.isNotEmpty()) PoolPolicyStore.load(app) // иначе списки стран заработают только после перезапуска
        } catch (e: Exception) { storage.log("BACKUP: списки из копии не применены: ${e.message}") }
        try { reloadSettingsFlows() } catch (e: Exception) { storage.log("BACKUP: не удалось обновить настройки на экране: ${e.message}") }
        refresh()
        PlanBus.notifyChanged()
        storage.log("BACKUP: импорт — " + report.notes.joinToString("; "))
        return tr("Импорт выполнен:\n") + report.text()
    }

    /** @return сколько сохранённых значений сброшено до рекомендуемых */
    fun resetRecommended(): Int {
        val dnsBefore = settings.dnsProvider
        val n = settings.resetRecommended()
        try { reloadSettingsFlows() } catch (e: Exception) { storage.log("SETTINGS: после сброса экран не обновился: ${e.message}") }
        storage.log("SETTINGS: возвращены рекомендуемые значения ($n)")
        // DNS вернулся к умолчанию, а туннель уже работает со старым: применить, и не оставлять пробу, которая откатила бы его обратно
        if (settings.dnsProvider != dnsBefore) {
            settings.dnsTrialAt = 0L
            _dnsTrialAt.value = 0L
            storage.log("DNS: после сброса ${DnsProvider.fromId(settings.dnsProvider).title} (был ${DnsProvider.fromId(dnsBefore).title})")
            PhoenixVpnService.reapplyDns(getApplication())
        }
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

    private val _detectText = MutableStateFlow(detectLabel())
    val detectText: StateFlow<String> = _detectText.asStateFlow()

    /** Последнее определение вместе со временем: «21:15 · чёрные списки …». Время нужно, чтобы старый результат не выглядел свежим. */
    private fun detectLabel(): String {
        val t = settings.lastDetect
        if (t.isBlank()) return ""
        val at = settings.lastDetectAt
        if (at <= 0L) return t
        val hhmm = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
        return "$hhmm · $t"
    }

    /** Определение чаще всего запускает служба (смена сети, проверка связи), а не кнопка: экран перечитывает результат из настроек. */
    fun refreshDetect() { _detectText.value = detectLabel() }

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
            ModeDetector.run(app, ignoreHold = true)
            refreshDetect()
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
        TargetSyncIO.merge(getApplication())
        _targets.value = TargetStore(storage.targetsFile).load()
        _bypassSites.value = BypassStore(storage.base, storage.mode).userSites()
    }

    /** Файлы целей всех режимов: правка списка (добавить, убрать, адрес, политика) делается везде, серверы у режимов свои. */
    private fun allTargetStores(): List<TargetStore> = Mode.entries.map { TargetStore(Storage(getApplication<Application>(), it).targetsFile) }

    /** @return null — добавлено, иначе текст ошибки */
    fun addTargetApp(pkg: String, label: String, checkUrl: String): String? {
        // у любого приложения есть адрес проверки: свой, известный или общий (проверка, что сервер вообще работает)
        val url = checkUrl.trim().ifBlank { KnownApps.urlOrDefault(pkg) }
        if (!url.startsWith("http", ignoreCase = true)) return tr("Адрес проверки должен начинаться с https://")
        val t0 = Target.app(pkg, label, url)
        val err = TargetStore(storage.targetsFile).add(t0)
        if (err == null) allTargetStores().forEach { it.add(t0) }
        reloadTargets()
        PlanBus.notifyChanged()
        return err
    }

    fun addTargetSite(input: String): String? {
        val t = Target.site(input) ?: return tr("Не похоже на адрес сайта")
        val err = TargetStore(storage.targetsFile).add(t)
        if (err == null) allTargetStores().forEach { it.add(t) }
        reloadTargets()
        PlanBus.notifyChanged()
        return err
    }

    fun removeTarget(id: String) {
        allTargetStores().forEach { it.remove(id) }
        reloadTargets()
        PlanBus.notifyChanged()
    }

    /** Меняет адрес проверки цели; пусто — вернуть известный или общий. @return null — сохранено, иначе текст ошибки */
    fun setTargetUrl(t: Target, url: String): String? {
        val u = url.trim().ifBlank { if (t.kind == TargetKind.APP) KnownApps.urlOrDefault(t.value) else "https://${t.value}/" }
        if (!u.startsWith("http", ignoreCase = true)) return tr("Адрес проверки должен начинаться с https://")
        // setUrl, а не update(t.copy(...)) — иначе правка несёт servers/verifiedAt того Target, который UI открыл
        // раньше, и стирает серверы, найденные фоном с тех пор (находка Фейбла, Targets/reserves #6)
        allTargetStores().forEach { it.setUrl(t.id, u) }
        reloadTargets()
        return null
    }

    fun setTargetPolicy(t: Target, p: Policy) {
        allTargetStores().forEach { it.setPolicy(t.id, p) }
        reloadTargets()
        PlanBus.notifyChanged()
    }

    fun verifyTargetsNow() {
        if (_busy.value.isNotEmpty()) return
        StopRequest.clear()
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
            MatrixRow(t.label, name, svc.map { s -> res?.status(s) ?: ServiceStatus.UNKNOWN })
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

    fun runNow() { StopRequest.clear(); Scheduler.runNow(getApplication<Application>()) }

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
