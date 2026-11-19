package com.phoenix.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phoenix.app.PhoenixTheme
import com.phoenix.app.ServiceStatus

private enum class Page(val title: String) {
    HOME("Главная"), SERVERS("Серверы"), SOURCES("Источники"), LOG("Журнал"), SETTINGS("Настройки"),
}

/** Цвета состояний: рабочий, частично, закрыт, неизвестно. */
@Composable
internal fun statusColor(s: ServiceStatus): Color {
    val dark = isSystemInDarkTheme()
    return when (s) {
        ServiceStatus.OPEN -> PhoenixTheme.ok(dark)
        ServiceStatus.PARTIAL -> PhoenixTheme.amber(dark)
        ServiceStatus.CLOSED -> MaterialTheme.colorScheme.error
        ServiceStatus.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }
}

@Composable
internal fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun PhoenixWindow(engine: Engine) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) PhoenixTheme.dark else PhoenixTheme.light) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var page by remember { mutableStateOf(Page.HOME) }
            Row(Modifier.fillMaxSize()) {
                Sidebar(page, { page = it }, engine)
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                    when (page) {
                        Page.HOME -> HomePage(engine)
                        Page.SERVERS -> ServersPage(engine)
                        Page.SOURCES -> SourcesPage(engine)
                        Page.LOG -> LogPage(engine)
                        Page.SETTINGS -> SettingsPage(engine)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(page: Page, onPage: (Page) -> Unit, engine: Engine) {
    val busy by engine.busy.collectAsState()
    val conn by engine.connection.collectAsState()
    Column(Modifier.width(210.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Phoenix", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        for (p in Page.entries) {
            if (p == page) {
                Button(onClick = { onPage(p) }, modifier = Modifier.fillMaxWidth()) { Text(p.title) }
            } else {
                TextButton(onClick = { onPage(p) }, modifier = Modifier.fillMaxWidth()) { Text(p.title) }
            }
        }
        Spacer(Modifier.weight(1f))
        val c = conn
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val st = when {
                c == null -> ServiceStatus.UNKNOWN
                c.healthy == false -> ServiceStatus.CLOSED
                else -> ServiceStatus.OPEN
            }
            Dot(statusColor(st))
            Text(if (c == null) "Не подключено" else "Подключено", style = MaterialTheme.typography.bodyMedium)
        }
        busy?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HomePage(engine: Engine) {
    val busy by engine.busy.collectAsState()
    val progress by engine.progress.collectAsState()
    val conn by engine.connection.collectAsState()
    val snap by engine.snapshot.collectAsState()
    val msg by engine.message.collectAsState()
    val core by engine.coreVersion.collectAsState()
    val best = remember(snap) { snap.servers.firstOrNull { it.alive == true } }
    val aliveCount = remember(snap) { snap.servers.count { it.alive == true } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Главная", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val c = conn
                if (c != null) {
                    Text("Подключено", style = MaterialTheme.typography.titleLarge)
                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "SOCKS 127.0.0.1:${engine.settings.socksPort} · HTTP 127.0.0.1:${engine.settings.httpPort}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (c.healthy) {
                            true -> "Сервер отвечает"
                            false -> "Сервер не отвечает — ищу другой"
                            null -> "Проверяю сервер…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { engine.disconnect() }) { Text("Отключить") }
                } else {
                    Text("Не подключено", style = MaterialTheme.typography.titleLarge)
                    Text(
                        best?.let { "Лучший сейчас: ${it.name} (${it.cc})" } ?: "Нет проверенных серверов: нажмите «Собрать и проверить»",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { engine.connectBest() }, enabled = busy == null && core != null && best != null) { Text("Подключиться к лучшему") }
                }
            }
        }

        if (core == null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ядро Xray не установлено", style = MaterialTheme.typography.titleMedium)
                    Text("Оно нужно для проверки серверов и подключения. Приложение скачает его с GitHub (XTLS/Xray-core, около 16 МБ) и проверит контрольную сумму.")
                    Button(onClick = { engine.installCore() }, enabled = busy == null) { Text("Установить ядро") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Серверы", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (snap.servers.isEmpty()) "Список пуст — соберите конфиги." else "В списке ${snap.servers.size}, отвечают $aliveCount",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { engine.refreshAll() }, enabled = busy == null) { Text("Собрать и проверить") }
                    OutlinedButton(onClick = { engine.checkNow() }, enabled = busy == null) { Text("Только проверить") }
                }
                if (busy != null) {
                    Text(busy ?: "", style = MaterialTheme.typography.bodyMedium)
                    if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (msg.isNotBlank()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(msg, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
