@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

// Вкладка «Настройки», баннер и страница обновления (вынесено из MainActivity.kt без изменения поведения).

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
        if (!LiteProfile.ON) { // Lite: SIM нет, строки про мобильную сеть скрыты
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
                tr("Касается автоматической перепроверки, подбора серверов для целей и замеров скорости. Поиск сервера при обрыве не ограничивается никогда."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
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
internal fun UpdateBanner(vm: MainViewModel, onMore: () -> Unit) {
    val context = LocalContext.current
    val u by vm.update.collectAsStateWithLifecycle()
    val current = remember { UpdateChecker.currentVersion(context) }
    if (!u.newer(current)) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Доступна новая версия {}", u.tag), style = MaterialTheme.typography.titleMedium)
            if (u.reinstall) Text(tr("Потребуется переустановка: сначала сохраните резервную копию"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, u.url.ifEmpty { UpdateCheck.PAGE_URL }) }) { Text(tr("Открыть страницу загрузки")) }
                TextButton(onClick = onMore) { Text(tr("Обновления")) }
            }
        }
    }
}

internal fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** Страница «Обновления»: версия, когда проверяли, кнопка принудительной проверки. */
@Composable
internal fun UpdatePage(vm: MainViewModel) {
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
        if (u.newer(current)) InstallControls(vm, u.reinstall)
        Text(
            tr("Приложение раз в сутки смотрит страницу релизов на GitHub; при выходе новой версии придёт отдельное уведомление. Установить её можно кнопкой «Скачать и установить» или вручную: скачайте APK на странице релиза."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** Кнопка «Скачать и установить» с ходом загрузки; появляется, только если в релизе есть файл APK. */
@Composable
internal fun InstallControls(vm: MainViewModel, reinstall: Boolean) {
    val context = LocalContext.current
    val p by vm.install.collectAsStateWithLifecycle()
    val hasApk = remember { AppSettings(context).updateApkUrl.isNotEmpty() }
    if (!hasApk) return
    val backup by vm.reinstallBackup.collectAsStateWithLifecycle()
    val needs = reinstall || p.stage == InstallProgress.Stage.REINSTALL_READY
    val backupOk = backup.isNotEmpty() && backup != "-"
    val busy = p.stage == InstallProgress.Stage.DOWNLOADING || p.stage == InstallProgress.Stage.VERIFYING || p.stage == InstallProgress.Stage.INSTALLING
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (needs) {
            Text(
                tr("Это обновление нельзя поставить поверх: оно подписано другим ключом, а Android не разрешает сменить ключ без удаления приложения. При удалении пропадут настройки и данные, поэтому сначала сохраните копию в Загрузки — она останется на телефоне."),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { vm.saveBackupBeforeReinstall() }) { Text(tr("1. Сохранить копию в Загрузки")) }
            if (backup == "-") Text(tr("Не удалось сохранить копию"), color = MaterialTheme.colorScheme.error)
            if (backupOk) Text(tr("Копия сохранена: Загрузки/Phoenix/{}", backup), style = MaterialTheme.typography.bodyMedium)
        }
        Button(enabled = !busy && (!needs || backupOk), onClick = { vm.installUpdate() }) { Text(if (needs) tr("2. Скачать обновление") else tr("Скачать и установить")) }
        when (p.stage) {
            InstallProgress.Stage.DOWNLOADING -> Text(tr("Скачиваю… {}%", p.percent), style = MaterialTheme.typography.bodyMedium)
            InstallProgress.Stage.VERIFYING -> Text(tr("Проверяю файл…"), style = MaterialTheme.typography.bodyMedium)
            InstallProgress.Stage.INSTALLING -> Text(tr("Передаю на установку: подтвердите в системном окне"), style = MaterialTheme.typography.bodyMedium)
            InstallProgress.Stage.NEED_PERMISSION -> {
                Text(tr("Разрешите Phoenix устанавливать приложения, затем нажмите «Скачать и установить» ещё раз"), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { UpdateInstaller.openPermissionSettings(context) }) { Text(tr("Открыть разрешение")) }
            }
            InstallProgress.Stage.FAILED -> Text(tr("Не удалось: {}", p.error), color = MaterialTheme.colorScheme.error)
            InstallProgress.Stage.REINSTALL_READY -> {
                Text(
                    tr("Файл сохранён: Загрузки/Phoenix/{}. Теперь удалите Phoenix, затем откройте этот файл (приложение «Файлы» или «Загрузки») и установите его. После запуска: Настройки → Резервная копия → Импорт из файла, выберите сохранённую копию.", p.file),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { UpdateInstaller.requestUninstall(context) }) { Text(tr("3. Удалить Phoenix в настройках")) }
            }
            InstallProgress.Stage.IDLE -> {}
        }
    }
}
