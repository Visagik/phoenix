@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Раздел «Диагностика»: сводка «что сейчас», журнал с фильтрами и поиском, отчёт последнего сбора. */
@Composable
fun DiagPage(vm: MainViewModel) {
    // 0 — сводка; 1 — журнал; 2 — отчёт сбора; 3 — расход, статистика, «Можно вернуть», данные
    var sub by remember { mutableStateOf(0) }
    BackHandler(enabled = sub != 0) { sub = 0 }
    Column(Modifier.fillMaxSize()) {
        if (sub != 0) TextButton(onClick = { sub = 0 }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("‹ " + tr("Сводка")) }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> SummaryPage(vm) { sub = it }
                1 -> LogPage(vm)
                2 -> ReportTab(vm)
                else -> DiagExtras(vm)
            }
        }
    }
}

@Composable
private fun SummaryPage(vm: MainViewModel, onMore: (Int) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var big by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var matrix by remember { mutableStateOf<Pair<List<CheckService>, List<MatrixRow>>>(emptyList<CheckService>() to emptyList()) }
    var day by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            summary = vm.diagSummary()
            matrix = vm.diagMatrix()
            big = vm.diagBig()
            day = vm.logDay()
        }
    }
    val counts = remember(day) { Diagnostics.counts(day) }
    val problems = remember(day) { day.filter { Diagnostics.isProblem(it) }.takeLast(8) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val headline = remember(counts) { Diagnostics.headline(counts) }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Text(
                when (headline) {
                    Diagnostics.Headline.OK -> tr("Всё работает")
                    Diagnostics.Headline.UNSTABLE -> tr("Сервер сменился {} раз за сутки: сеть нестабильна", counts.failovers)
                    Diagnostics.Headline.PROBLEMS -> tr("Есть проблемы: {} за сутки. Подробности в журнале", counts.problems)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (big.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                big.forEach { (num, cap) ->
                    Card(Modifier.weight(1f), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(num, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            Text(cap, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (matrix.second.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Цели и сервисы"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    TargetMatrix(matrix.first, matrix.second)
                }
            }
        }
        var all by remember { mutableStateOf(false) }
        TextButton(onClick = { all = !all }) { Text(if (all) tr("Скрыть подробности") else tr("Подробнее")) }
        if (all) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMore(1) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Журнал")) }
                OutlinedButton(onClick = { onMore(2) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Отчёт сбора")) }
                OutlinedButton(onClick = { onMore(3) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Что приложение делает само, статистика, данные")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { tick++ }) { Text(tr("Обновить")) }
                    OutlinedButton(onClick = {
                        val text = Diagnostics.bundle(summary, counts, vm.logLines("", 400))
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("phoenix-diagnostics", text))
                        Toast.makeText(context, tr("Диагностика скопирована"), Toast.LENGTH_SHORT).show()
                    }) { Text(tr("Копировать всё")) }
                    OutlinedButton(onClick = {
                        val text = Diagnostics.bundle(summary, counts, vm.logLines("", 400))
                        val ok = Exporter.saveToDownloads(context, "phoenix-diagnostics.txt", text)
                        Toast.makeText(context, if (ok) tr("Сохранено: {}", Exporter.where(context)) else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
                    }) { Text(tr("В файл")) }
                }
            }
        }
        // цели показаны таблицей выше; их длинные строки в сводке не повторяем
        val targetPrefix = tr("Цель «{}»").substringBefore("{}")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.filter { !it.first.startsWith(targetPrefix) && it.first != tr("Свежие проверки (сутки)") && it.first != tr("Перепроверка конфигов") }.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("За последние сутки"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("переключений сервера {} · смен режима {} · смен сети {}\nсбоев маршрутов {} · обновлений целей {} · проблемных записей {}", counts.failovers, counts.modeSwitches, counts.networkChanges, counts.routeFails, counts.targetUpdates, counts.problems),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (problems.isNotEmpty()) {
            Text(tr("Последние проблемы"), style = MaterialTheme.typography.titleSmall)
            Text(
                problems.joinToString("\n") { Diagnostics.pretty(it) },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

/** Отфильтрованный журнал, готовый к показу (считается на фоновом потоке). */
private class LogView(val lines: List<String>, val shown: List<String>, val text: String, val pretty: List<Pair<String, Boolean>>) {
    companion object {
        val EMPTY = LogView(emptyList(), emptyList(), "", emptyList())
        fun build(all: List<String>, group: String, query: String, problemsOnly: Boolean): LogView {
            val lines = Diagnostics.filter(all, group, query, problemsOnly)
            val shown = lines.takeLast(800)
            val pretty = shown.map { Diagnostics.pretty(it) to Diagnostics.isProblem(it) }
            return LogView(lines, shown, pretty.joinToString("\n") { it.first }, pretty)
        }
    }
}

@Composable
fun LogPage(vm: MainViewModel) {
    val context = LocalContext.current
    var group by remember { mutableStateOf("Всё") } // ключ группы — русское название (по нему фильтрует Diagnostics), на экране оно переводится
    var query by remember { mutableStateOf("") }
    var problemsOnly by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val ioScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MemInfo.log(context, "открыт журнал") } }
    LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { vm.logLines("", 40_000) } }
    // фильтр по суткам журнала (до 40 000 строк, регулярки) и разбор последних строк — не в основном потоке, с паузой после ввода
    val view by produceState(LogView.EMPTY, all, group, query, problemsOnly) {
        if (query.isNotEmpty()) delay(300)
        value = withContext(Dispatchers.Default) { LogView.build(all, group, query, problemsOnly) }
    }
    val lines = view.lines
    val shown = view.shown
    val text = view.text

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Diagnostics.GROUPS.forEach { (title, _) ->
                FilterChip(selected = group == title, onClick = { group = title }, label = { Text(tr(title)) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(tr("Поиск по журналу")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = problemsOnly, onCheckedChange = { problemsOnly = it })
            Text(tr("  Только проблемы").trim(), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        var askClear by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        val copyLog = {
            // redact() на 800 строках — регулярные выражения: в главном потоке это давало ANR (журнал 05.11, стек Diagnostics.redact)
            ioScope.launch {
                val clean = withContext(Dispatchers.IO) { Diagnostics.redact(text) }
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("phoenix-log", clean))
                Toast.makeText(context, tr("Журнал скопирован"), Toast.LENGTH_SHORT).show()
            }
        }
            // два вида файла: только последние сутки (короче и проще отправить) или весь журнал (всё, что накопилось)
            val saveLog = { dayOnly: Boolean ->
                // до 40 000 строк: сборка, вычистка ключей и запись в «Загрузки» занимают секунды — в главном потоке это был «не отвечает»
                saving = true
                Toast.makeText(context, tr("Сохраняю журнал…"), Toast.LENGTH_SHORT).show()
                ioScope.launch {
                    val ok = try {
                        withContext(Dispatchers.IO) {
                            val part = if (dayOnly) Diagnostics.since(lines, java.time.OffsetDateTime.now().minusHours(24).toString()) else lines
                            val full = part.joinToString("\n") { Diagnostics.pretty(it) }
                            full.isNotEmpty() && Exporter.saveToDownloads(context, if (dayOnly) "phoenix-log-24h.txt" else "phoenix-log.txt", Diagnostics.redact(full))
                        }
                    } catch (_: Throwable) {
                        false
                    }
                    saving = false
                    Toast.makeText(context, if (ok) tr("Сохранено: {}", Exporter.where(context)) else tr("Не удалось сохранить журнал (за выбранный период записей нет?)"), Toast.LENGTH_SHORT).show()
                }
            }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(onClick = { tick++ }) { Text(tr("Обновить")) }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("Копировать")) }, enabled = shown.isNotEmpty(), onClick = { menu = false; copyLog() })
                    DropdownMenuItem(text = { Text(if (saving) tr("Сохраняю…") else tr("В файл: 24 часа")) }, enabled = lines.isNotEmpty() && !saving, onClick = { menu = false; saveLog(true) })
                    DropdownMenuItem(text = { Text(tr("В файл: весь")) }, enabled = lines.isNotEmpty() && !saving, onClick = { menu = false; saveLog(false) })
                    DropdownMenuItem(text = { Text(tr("Очистить")) }, onClick = { menu = false; askClear = true })
                }
            }
        }
        if (askClear) ConfirmDialog(tr("Очистить журнал?"), tr("Записи будут удалены без возможности вернуть."), onYes = { vm.clearLogs(); tick++ }, onDismiss = { askClear = false })
        Text(tr("Записей: {} из {} (на экране последние {}; «В файл» сохраняет по фильтру: за 24 часа или весь журнал). Журнал копится до 5 МБ на режим.", lines.size, all.size, shown.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lines.isEmpty()) {
            Hint(tr("Записей по этому фильтру нет."))
        } else {
            // самые свежие записи внизу; список прокручивается
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val pretty = view.pretty
            LaunchedEffect(pretty) { if (pretty.isNotEmpty()) listState.scrollToItem(pretty.lastIndex) }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(pretty.size, key = { it }) { i ->
                    val (line, bad) = pretty[i]
                    Text(
                        line,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}


// ---------------------------------------------------------------- Экспорт и импорт
