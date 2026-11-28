@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

// Карточка VPN и домашняя панель (вынесено из MainActivity.kt без изменения поведения).

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

    val tickerOn = remember { AppSettings(context).factsTicker }
    if (tickerOn) FactTicker()
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
internal fun PowerGlyph(color: androidx.compose.ui.graphics.Color) {
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
        // просьба снимается не сразу: между этапами цепочки (сбор → перепроверка → цели) список «сейчас делаю» на миг пустеет
        LaunchedEffect(doing.isEmpty()) { if (doing.isEmpty()) { kotlinx.coroutines.delay(StopRequest.CHAIN_MS); StopRequest.clear() } }
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
