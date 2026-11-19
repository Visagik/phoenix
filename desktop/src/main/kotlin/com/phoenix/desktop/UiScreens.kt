package com.phoenix.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.app.Mode
import com.phoenix.app.ServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- серверы

@Composable
internal fun ServersPage(engine: Engine) {
    val snap by engine.snapshot.collectAsState()
    val busy by engine.busy.collectAsState()
    val conn by engine.connection.collectAsState()
    var query by remember { mutableStateOf("") }
    var onlyAlive by remember { mutableStateOf(false) }
    // фильтр считается один раз на изменение списка или запроса, а не на каждый кадр прокрутки
    val shown = remember(snap, query, onlyAlive) {
        val q = query.trim()
        snap.servers.filter { s ->
            (!onlyAlive || s.alive == true) && (q.isEmpty() || s.name.contains(q, ignoreCase = true) || s.cc.equals(q, ignoreCase = true))
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Серверы", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Поиск по имени или стране (DE, NL…)") }, singleLine = true, modifier = Modifier.width(340.dp))
            Switch(onlyAlive, { onlyAlive = it })
            Text("Только рабочие")
            Spacer(Modifier.weight(1f))
            Text("${shown.size} из ${snap.servers.size}", style = MaterialTheme.typography.bodyMedium)
        }
        if (snap.services.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Сервисы:", style = MaterialTheme.typography.bodySmall)
                for (s in snap.services) Text("${s.id} — ${s.title}", style = MaterialTheme.typography.bodySmall)
                Dot(statusColor(ServiceStatus.OPEN), 8); Text("открыт", style = MaterialTheme.typography.bodySmall)
                Dot(statusColor(ServiceStatus.PARTIAL), 8); Text("частично", style = MaterialTheme.typography.bodySmall)
                Dot(statusColor(ServiceStatus.CLOSED), 8); Text("закрыт", style = MaterialTheme.typography.bodySmall)
                Dot(statusColor(ServiceStatus.UNKNOWN), 8); Text("не проверен", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        if (shown.isEmpty()) {
            Text(
                if (snap.servers.isEmpty()) "Список пуст. На главной нажмите «Собрать и проверить»." else "Ничего не найдено.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.link }) { row ->
                ServerLine(row, snap.services.map { it.id }, connected = conn?.link == row.link, enabled = busy == null) { engine.connect(row.link, row.name) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ServerLine(row: ServerRow, serviceIds: List<String>, connected: Boolean, enabled: Boolean, onConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Dot(statusColor(if (row.alive == true) ServiceStatus.OPEN else if (row.alive == false) ServiceStatus.CLOSED else ServiceStatus.UNKNOWN))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${row.protocol.uppercase()} · ${row.cc} · ${row.days} дн.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (row.alive == true && row.latencyMs >= 0) "${row.latencyMs} мс" else "", Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (id in serviceIds) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Dot(statusColor(row.services[id] ?: ServiceStatus.UNKNOWN), 9)
                    Text(id, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (connected) {
            Text("подключён", Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = onConnect, enabled = enabled && row.alive != false, modifier = Modifier.width(110.dp)) { Text("Подключить") }
        }
    }
}

// ---------------------------------------------------------------- источники

@Composable
internal fun SourcesPage(engine: Engine) {
    val busy by engine.busy.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val sources by produceState(emptyList<SourceRow>(), busy, tick) { value = withContext(Dispatchers.IO) { engine.sources() } }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Источники", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Откуда приложение берёт конфиги. Встроенные источники зависят от режима (${engine.settings.mode.title}); свои ссылки добавляются сюда.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it; error = "" }, label = { Text("Ссылка на список конфигов") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = {
                val e = engine.addSource(url)
                if (e == null) { url = ""; tick++ } else error = e
            }, enabled = url.isNotBlank()) { Text("Добавить") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(sources, key = { it.url }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Dot(statusColor(if (s.active) ServiceStatus.OPEN else ServiceStatus.CLOSED))
                    Column(Modifier.weight(1f)) {
                        Text(s.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        val sub = listOfNotNull(if (s.mine) "своя" else null, s.status.ifBlank { null }, s.lastDate.ifBlank { null }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.mine) TextButton(onClick = { engine.removeSource(s.url); tick++ }) { Text("Удалить") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- журнал

@Composable
internal fun LogPage(engine: Engine) {
    val busy by engine.busy.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val text by produceState("", busy, tick) {
        value = withContext(Dispatchers.IO) { engine.logTail().lines().asReversed().joinToString("\n") }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Журнал", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { tick++ }) { Text("Обновить") }
        }
        Text("Новые записи сверху.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(text.ifBlank { "Пока пусто." }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- настройки

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
        HorizontalDivider()
    }
}

@Composable
private fun SwitchLine(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, label) in options) {
            if (v == selected) Button(onClick = { onSelect(v) }) { Text(label) } else OutlinedButton(onClick = { onSelect(v) }) { Text(label) }
        }
    }
}

@Composable
internal fun SettingsPage(engine: Engine) {
    val s = engine.settings
    val busy by engine.busy.collectAsState()
    val core by engine.coreVersion.collectAsState()
    var mode by remember { mutableStateOf(s.mode) }
    var interval by remember { mutableStateOf(s.intervalHours) }
    var proxy by remember { mutableStateOf(s.systemProxy) }
    var failover by remember { mutableStateOf(s.autoFailover) }
    var autoConnect by remember { mutableStateOf(s.autoConnect) }
    var checkSvc by remember { mutableStateOf(s.checkServices) }
    var minimized by remember { mutableStateOf(s.startMinimized) }
    var autostart by remember { mutableStateOf(Autostart.isEnabled()) }
    var autostartError by remember { mutableStateOf("") }
    var socks by remember { mutableStateOf(s.socksPort.toString()) }
    var http by remember { mutableStateOf(s.httpPort.toString()) }
    var bypass by remember { mutableStateOf(s.bypass.joinToString(", ")) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)

        Section("Ядро Xray") {
            Text(core?.let { "Установлено: $it" } ?: "Не установлено", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { engine.installCore() }, enabled = busy == null) { Text(if (core == null) "Установить ядро" else "Обновить ядро") }
        }

        Section("Режим") {
            Text("У каждого режима свои источники и своя статистика.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice(Mode.entries.map { it to it.title }, mode) { mode = it; s.mode = it; engine.markModeChanged() }
        }

        Section("Автоматический сбор") {
            Text("Пока приложение запущено (оно может сидеть в трее), сбор и проверка идут по расписанию.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice(listOf(0 to "Выключено", 3 to "3 ч", 6 to "6 ч", 12 to "12 ч", 24 to "24 ч"), interval) { interval = it; s.intervalHours = it }
            SwitchLine("Проверять выбранные сервисы", "Telegram, Claude, YouTube и другие — у лучших серверов после сбора", checkSvc) { checkSvc = it; s.checkServices = it }
            SwitchLine("Подключаться сам", "После автосбора, если подключения ещё нет", autoConnect) { autoConnect = it; s.autoConnect = it }
        }

        Section("Подключение") {
            SwitchLine("Системный прокси", "Включать при подключении и возвращать прежние настройки при отключении (gsettings: Cinnamon, GNOME, MATE)", proxy) { proxy = it; s.systemProxy = it }
            SwitchLine("Переключаться при обрыве", "Если сервер перестал отвечать, взять следующий по рейтингу", failover) { failover = it; s.autoFailover = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(socks, { v -> socks = v.filter { it.isDigit() }.take(5); socks.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { s.socksPort = it } }, label = { Text("Порт SOCKS") }, singleLine = true, modifier = Modifier.width(150.dp))
                OutlinedTextField(http, { v -> http = v.filter { it.isDigit() }.take(5); http.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { s.httpPort = it } }, label = { Text("Порт HTTP") }, singleLine = true, modifier = Modifier.width(150.dp))
            }
            Text("Порты применяются при следующем подключении.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                bypass, { bypass = it; s.bypass = it.split(',', ' ', '\n').map { d -> d.trim() }.filter { d -> d.isNotEmpty() } },
                label = { Text("Сайты напрямую, мимо сервера (через запятую: ru, example.org)") }, modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Запуск") {
            SwitchLine("Запускать при входе в систему", "Свернутым в трей", autostart) {
                val e = Autostart.set(it)
                if (e == null) { autostart = it; autostartError = "" } else autostartError = e
            }
            if (autostartError.isNotBlank()) Text(autostartError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            SwitchLine("Открываться свернутым", "Окно появится по значку в трее", minimized) { minimized = it; s.startMinimized = it }
        }

        Section("О программе") {
            Text("Phoenix для Linux 3.9.65", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text("Данные: ${Paths.dataDir.absolutePath}", style = MaterialTheme.typography.bodySmall) }
            Text("Без окна: phoenix --collect (сбор и проверка, затем выход) — для cron или systemd-таймера.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
