@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

// Вкладки «Отчёт» и «Источники» (вынесено из MainActivity.kt без изменения поведения).

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
                        if (ok) tr("Отчёт сохранён: {}", Exporter.where(context)) else tr("Отчёта пока нет"),
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
                Mode.entries.filter { it !in LiteProfile.skipModes() }.forEach { m -> // Lite: белых списков нет
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
