@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

// Главный экран, меню настроек, страницы (вынесено из MainActivity.kt без изменения поведения).

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

internal enum class Page(val title: String, val hint: String) {
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
    INTRO("Обучение", "пройти заново: разрешения, серверы, подсказки"),
    WHATSNEW("Что нового", "список изменений по версиям"),
    TIPS("Фишки", "плитки в шторке, ярлыки, виджет, «Поделиться» и другие возможности"),
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
    // обучение открыло подстраницу («свой сервер», «Фишки»): «назад» возвращает на последнюю карточку, а не в главное меню
    var introReturn by rememberSaveable { mutableStateOf(false) }
    var introTail by rememberSaveable { mutableStateOf(false) }
    val introSteps = remember(onboarding, introForce, introTail) {
        if (!onboarding) emptyList()
        else if (introTail) listOf(Onboarding.Step.TIPS)
        else Onboarding.steps(appSettings.onboardingSeen, onboardingEnv(context), introForce)
    }
    fun closeSubpage() {
        page = null
        if (introReturn) { introReturn = false; introTail = true; inSettings = false; onboarding = true }
    }
    // «Что нового»: после обновления один раз; на новой установке вместо него обучение (версия просто отмечается как виденная)
    val freshInstall = remember { Onboarding.steps(appSettings.onboardingSeen, onboardingEnv(context)).contains(Onboarding.Step.WELCOME) }
    var whatsNewOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(onboarding) {
        if (freshInstall) appSettings.whatsNewSeen = currentVersion
        else if (!onboarding && WhatsNew.shouldShow(appSettings.whatsNewSeen, currentVersion, false)) whatsNewOpen = true
    }
    if (whatsNewOpen && !onboarding) WhatsNewDialog(
        currentVersion,
        onClose = { appSettings.whatsNewSeen = currentVersion; whatsNewOpen = false },
        onAll = { appSettings.whatsNewSeen = currentVersion; whatsNewOpen = false; inServers = false; inSettings = true; page = Page.WHATSNEW },
    )

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
        if (inServers) inServers = false else if (page != null) closeSubpage() else inSettings = false
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

    if (onboarding) OnboardingScreen(
        introSteps, vm,
        onDone = { onboarding = false; introForce = false; introTail = false },
        onOpenTargets = { onboarding = false; introReturn = true; inServers = false; inSettings = true; page = Page.TARGETS },
        onOpenTips = { onboarding = false; introReturn = true; inServers = false; inSettings = true; page = Page.TIPS },
    ) else Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (inServers) tr("Серверы") else if (!inSettings) "Phoenix" else if (page == Page.TARGETS || page == Page.BYPASS) tr("Приложения и сайты") else page?.title?.let { tr(it) } ?: tr("Ещё")) },
                navigationIcon = {
                    // «назад» нужен только внутри подстраницы «Ещё»; разделы переключаются нижней панелью
                    if (inSettings && page != null) IconButton(onClick = { closeSubpage() }) {
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
                    if (!LiteProfile.ON) TabRow(selectedTabIndex = if (page == Page.BYPASS) 1 else 0) {
                        Tab(selected = page == Page.TARGETS, onClick = { page = Page.TARGETS }, text = { Text(tr("Свой сервер"), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        Tab(selected = page == Page.BYPASS, onClick = { page = Page.BYPASS }, text = { Text(tr("Мимо VPN"), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    if (page == Page.BYPASS || LiteProfile.ON) VpnTab(vm, onOpenServices = { page = Page.SERVICES }) else TargetsPage(vm)
                }
                Page.CONNECTION -> ConnectionPage(vm)
                Page.COLLECT -> SettingsTab(vm)
                Page.FIRMWARE -> FirmwarePage()
                Page.SOURCES -> SourcesTab(vm)
                Page.BACKUP -> BackupPage(vm)
                Page.UPDATE -> UpdatePage(vm)
                Page.DIAG -> DiagPage(vm)
                Page.INTRO -> {}
                Page.TIPS -> TipsPage(onOpenTargets = { page = Page.TARGETS }, onOpenSources = { page = Page.SOURCES })
                Page.WHATSNEW -> WhatsNewPage()
                Page.SERVICES -> ServicesPage(vm)
                Page.COUNTRIES -> CountriesPage(vm)
            }
        }
    }
}

/** Раздел «Ещё»: четыре группы, строки со значком, подписью и текущим значением справа. */
@Composable
internal fun SettingsMenu(modeTitle: String, version: String, targetsCount: Int, mineCount: Int, schedule: String, animations: Boolean, onAnimations: (Boolean) -> Unit, theme: Int, onTheme: (Int) -> Unit, attention: List<Attention.Item>, newVersion: String, onOpen: (Page) -> Unit) {
    // Lite: без режима блокировок (всегда чёрные списки), без «Свой сервер» и обновлений
    val groups = listOf(
        "Подключение" to listOf(Page.MODE, Page.CONNECTION, Page.FIRMWARE),
        "Приложения и сайты" to listOf(Page.BYPASS, Page.TARGETS),
        "Серверы" to listOf(Page.COLLECT, Page.SOURCES, Page.SERVICES, Page.COUNTRIES),
        "Фишки и обучение" to listOf(Page.TIPS, Page.WHATSNEW, Page.INTRO),
        "Помощь и данные" to listOf(Page.DIAG, Page.BACKUP, Page.UPDATE),
    ).map { (g, pages) -> g to pages.filter { !LiteProfile.ON || it !in setOf(Page.MODE, Page.TARGETS, Page.UPDATE) } }
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
        Page.TIPS -> Icons.Filled.Info
        Page.WHATSNEW -> Icons.Filled.Star
        else -> Icons.Filled.Menu
    }
    fun value(p: Page): String = when (p) {
        Page.MODE -> modeTitle
        Page.UPDATE -> if (Attention.Item.UPDATE in attention) "● $newVersion" else version
        Page.FIRMWARE -> Attention.backgroundCount(attention).let { if (it > 0) tr("● нужно {}", it) else "" }
        Page.TARGETS -> if (targetsCount > 0) tr("целей: {}", targetsCount) else ""
        Page.SOURCES -> if (mineCount > 0) tr("своих: {}", mineCount) else ""
        Page.COLLECT -> schedule
        Page.WHATSNEW -> WhatsNew.ENTRIES.firstOrNull()?.version ?: ""
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
        if (LiteProfile.ON) {
            Text(tr("Лёгкий режим (сборка Lite): отключены белые списки, цели, обновления"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
