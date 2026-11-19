package com.phoenix.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Что на этом телефоне уже выдано и что к нему относится; считается при каждом возврате из системных окон. */
fun onboardingEnv(context: Context): Onboarding.Env {
    val bg = Background.status(context)
    val vpnOk = try { VpnService.prepare(context) == null } catch (_: Throwable) { false }
    val notifOk = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }
    val vendor = try { Background.vendor() } catch (_: Throwable) { OemGuide.Vendor.OTHER }
    val hasServers = Mode.entries.any { try { Storage(context, it).latestEnriched() != null } catch (_: Throwable) { false } }
    return Onboarding.Env(
        vpnGranted = vpnOk,
        notificationsRelevant = Build.VERSION.SDK_INT >= 33,
        notificationsGranted = notifOk,
        batteryOk = bg.ignoringBatteryOptimization,
        exactRelevant = Build.VERSION.SDK_INT >= 31,
        exactOk = bg.exactAlarmAllowed,
        autostartRelevant = vendor == OemGuide.Vendor.XIAOMI || vendor == OemGuide.Vendor.HUAWEI || vendor == OemGuide.Vendor.OPPO || vendor == OemGuide.Vendor.VIVO,
        autostartConfirmed = AppSettings(context).autostartConfirmed,
        hasServers = hasServers,
    )
}

/**
 * Первый запуск: по одному вопросу на экран. Под каждым — что будет, если пропустить. После возврата из системного окна
 * всегда есть «Продолжить» (на Xiaomi окно может вернуть без результата). Статистику использования и спецвозможности здесь не просим.
 */
@Composable
fun OnboardingScreen(steps: List<Onboarding.Step>, vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var index by rememberSaveable { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    var acted by remember { mutableStateOf(setOf<Onboarding.Step>()) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val running by RunState.running.collectAsState()
    val message by RunState.message.collectAsState()
    LaunchedEffect(running) { tick++ }
    val env = remember(tick) { onboardingEnv(context) }

    fun finish() {
        AppSettings(context).onboardingSeen = Onboarding.VERSION
        onDone()
    }
    if (steps.isEmpty()) {
        LaunchedEffect(Unit) { finish() }
        return
    }
    val i = index.coerceIn(0, steps.lastIndex)
    val step = steps[i]
    val last = i == steps.lastIndex
    val satisfied = Onboarding.satisfied(step, env)
    val done = step == Onboarding.Step.WELCOME || satisfied || step in acted

    fun next() {
        if (step == Onboarding.Step.AUTOSTART && step in acted) AppSettings(context).autostartConfirmed = true
        if (last) finish() else index = i + 1
    }
    fun act() {
        acted = acted + step
        when (step) {
            Onboarding.Step.VPN -> VpnService.prepare(context)?.let { consent.launch(it) }
            Onboarding.Step.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else Background.openNotificationSettings(context)
            Onboarding.Step.BACKGROUND -> Background.requestIgnoreBatteryOptimization(context)
            Onboarding.Step.ALARMS -> Background.requestExactAlarm(context)
            Onboarding.Step.AUTOSTART -> Background.openAutostart(context)
            Onboarding.Step.SERVERS -> vm.runNow()
            Onboarding.Step.WELCOME -> {}
        }
    }

    val title = when (step) {
        Onboarding.Step.WELCOME -> "Phoenix"
        Onboarding.Step.VPN -> tr("Разрешение VPN")
        Onboarding.Step.NOTIFICATIONS -> tr("Уведомления")
        Onboarding.Step.BACKGROUND -> tr("Работа в фоне")
        Onboarding.Step.ALARMS -> tr("Точные будильники")
        Onboarding.Step.AUTOSTART -> tr("Автозапуск")
        Onboarding.Step.SERVERS -> tr("Первые серверы")
    }
    val body = when (step) {
        Onboarding.Step.WELCOME -> listOf(
            tr("Сам находит рабочий сервер"),
            tr("Проверяет, открываются ли нужные сервисы"),
            tr("Отдельный сервер для отдельного приложения"),
            tr("Российские сайты — напрямую"),
        ).joinToString("\n") { "•  $it" }
        Onboarding.Step.VPN -> tr("Без этого Android не даст включить защиту.")
        Onboarding.Step.NOTIFICATIONS -> tr("Видно, что VPN работает, и его можно выключить из шторки.")
        Onboarding.Step.BACKGROUND -> tr("Иначе телефон выключит VPN и ночной сбор.")
        Onboarding.Step.ALARMS -> tr("Сбор в выбранное время, а не когда удобно системе.")
        Onboarding.Step.AUTOSTART -> tr("Разрешите автозапуск для Phoenix в настройках телефона и вернитесь сюда.")
        Onboarding.Step.SERVERS -> tr("Соберём список серверов — это займёт несколько минут.")
    }
    val ifSkipped = when (step) {
        Onboarding.Step.WELCOME -> ""
        Onboarding.Step.VPN -> tr("Если пропустить: системное окно появится при первом нажатии на круг.")
        Onboarding.Step.NOTIFICATIONS -> tr("Если пропустить: состояния VPN в шторке не будет.")
        Onboarding.Step.BACKGROUND -> tr("Если пропустить: возможны обрывы в фоне.")
        Onboarding.Step.ALARMS -> tr("Если пропустить: время сбора сдвигается.")
        Onboarding.Step.AUTOSTART -> tr("Если пропустить: VPN может пропадать после очистки памяти.")
        Onboarding.Step.SERVERS -> tr("Если пропустить: серверы можно собрать позже, меню ⋮ на главной.")
    }
    val icon = when (step) {
        Onboarding.Step.WELCOME -> Icons.Filled.Home
        Onboarding.Step.VPN -> Icons.Filled.Lock
        Onboarding.Step.NOTIFICATIONS -> Icons.Filled.Notifications
        Onboarding.Step.BACKGROUND -> Icons.Filled.Settings
        Onboarding.Step.ALARMS -> Icons.Filled.Refresh
        Onboarding.Step.AUTOSTART -> Icons.Filled.Warning
        Onboarding.Step.SERVERS -> Icons.Filled.Search
    }
    val actionLabel = when (step) {
        Onboarding.Step.AUTOSTART -> tr("Открыть настройки")
        Onboarding.Step.SERVERS -> tr("Собрать")
        else -> tr("Разрешить")
    }
    val scheme = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("{} из {}", i + 1, steps.size), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                TextButton(onClick = { finish() }) { Text(tr("Пропустить всё")) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = scheme.primary)
                }
                Spacer(Modifier.height(24.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(body, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = if (step == Onboarding.Step.WELCOME) TextAlign.Start else TextAlign.Center)
                if (satisfied && step != Onboarding.Step.WELCOME) {
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Уже готово"), style = MaterialTheme.typography.titleMedium, color = PhoenixTheme.ok(appDark()))
                }
                if (step == Onboarding.Step.SERVERS && running) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (!done && ifSkipped.isNotEmpty()) Text(ifSkipped, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            if (step != Onboarding.Step.WELCOME && !satisfied) {
                FilledTonalButton(onClick = { act() }, enabled = !(step == Onboarding.Step.SERVERS && running), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(actionLabel) }
                Spacer(Modifier.height(8.dp))
            }
            if (done) {
                Button(onClick = { next() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (step == Onboarding.Step.WELCOME) tr("Начать") else if (last) tr("Готово") else tr("Продолжить"))
                }
            } else {
                TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(tr("Пропустить")) }
            }
        }
    }
}
