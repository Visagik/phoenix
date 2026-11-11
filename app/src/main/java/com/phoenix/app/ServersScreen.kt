@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun toEntry(r: ConfigRow, c: CheckResult?, sel: List<CheckService>): ServerEntry {
    val fresh = c != null && !c.staleFor(r.raw)
    val live = c != null && fresh && !c.dead
    var ok = 0
    var part = 0
    var bl = 0
    var unk = 0
    if (live && c != null) {
        for (s in sel) {
            val ms = c.services[s.id]
            when {
                ms == null -> unk++
                ms >= 0 -> ok++
                ms == CheckServices.PARTIAL -> part++
                else -> bl++
            }
        }
    } else {
        unk = sel.size
    }
    return ServerEntry(
        raw = r.raw,
        name = r.displayName,
        protocol = r.protocol,
        reality = r.reality,
        cc = r.cc,
        days = r.days,
        pingMs = if (live && c != null) c.baselineMs else null,
        mbps = if (live && c != null && c.mbps >= 0) c.mbps else null,
        ok = ok,
        partial = part,
        blocked = bl,
        unknown = unk,
        checked = if (c != null && fresh) c.date else "",
        dead = c != null && fresh && c.dead,
        stale = c != null && !fresh,
    )
}

/** Окно «Серверы»: все показатели, фильтры и сортировка. Список с главного экрана перенесён сюда. */
@Composable
fun ServersPage(vm: MainViewModel, onConnect: (ConfigRow) -> Unit) {
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val selected by vm.selectedServices.collectAsStateWithLifecycle()
    val rechecking by RecheckState.running.collectAsStateWithLifecycle()
    val recheckProgress by RecheckState.progress.collectAsStateWithLifecycle()

    var sort by remember { mutableStateOf(ServerSort.BEST) }
    var desc by remember { mutableStateOf<Boolean?>(null) }
    var filter by remember { mutableStateOf(ServerFilter()) }

    val entries = remember(rows, checks, selected) { rows.map { toEntry(it, checks[CheckStore.keyOf(it.raw)], selected) } }
    val byRaw = remember(rows) { rows.associateBy { it.raw } }
    val visible = remember(entries, filter, sort, desc) { ServerTable.apply(entries, filter, sort, desc) }
    val countries = remember(entries) { ServerTable.countries(entries).take(12) }
    val protocols = remember(entries) { ServerTable.protocols(entries) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                (if (rechecking) "⏳ " + recheckProgress.ifBlank { tr("Перепроверяю…") } + "\n" else "") +
                    ServerTable.summary(entries) + tr(" · показано {}", visible.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(tr("Сортировка (нажмите на выбранный пункт, чтобы развернуть):"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerSort.entries.forEach { s ->
                    val effective = desc ?: ServerTable.defaultDesc(s)
                    val label = tr(s.title) + if (sort == s && s != ServerSort.BEST) (if (effective) " ↓" else " ↑") else ""
                    FilterChip(
                        selected = sort == s,
                        onClick = {
                            if (sort == s && s != ServerSort.BEST) desc = !(desc ?: ServerTable.defaultDesc(s))
                            else { sort = s; desc = null }
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(tr("Фильтры:"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter.onlyAlive, onClick = { filter = filter.copy(onlyAlive = !filter.onlyAlive) }, label = { Text(tr("Живые")) })
                FilterChip(selected = filter.onlyChecked, onClick = { filter = filter.copy(onlyChecked = !filter.onlyChecked) }, label = { Text(tr("Проверенные")) })
                FilterChip(selected = filter.onlyStable, onClick = { filter = filter.copy(onlyStable = !filter.onlyStable) }, label = { Text(tr("★ 3+ дня")) })
                FilterChip(selected = filter.minOk >= 3, onClick = { filter = filter.copy(minOk = if (filter.minOk >= 3) 0 else 3) }, label = { Text(tr("3+ сервиса")) })
                protocols.forEach { p ->
                    FilterChip(selected = filter.protocol == p, onClick = { filter = filter.copy(protocol = if (filter.protocol == p) "" else p) }, label = { Text(p.uppercase()) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                countries.forEach { cc ->
                    FilterChip(selected = filter.country == cc, onClick = { filter = filter.copy(country = if (filter.country == cc) "" else cc) }, label = { Text(cc) })
                }
            }
            OutlinedTextField(
                value = filter.query,
                onValueChange = { filter = filter.copy(query = it) },
                label = { Text(tr("Поиск по имени или стране")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = {
                    Exporter.copy(context, visible.joinToString("\n") { it.raw }, visible.size)
                }, enabled = visible.isNotEmpty()) { Text(tr("Копировать ({})", visible.size)) }
                OutlinedButton(onClick = { vm.recheckAll() }, enabled = !rechecking) { Text(if (rechecking) tr("Проверяю…") else tr("Перепроверить всё")) }
                OutlinedButton(onClick = { vm.recheckApps() }, enabled = !rechecking) { Text(tr("Только приложения")) }
                OutlinedButton(onClick = { Exporter.openV2RayNG(context) }) { Text("V2RayNG") }
                OutlinedButton(onClick = {
                    val text = vm.enrichedText()
                    val ok = text.isNotEmpty() && Exporter.saveToDownloads(context, Storage.ENRICHED_NAME, text)
                    Toast.makeText(context, if (ok) tr("Сохранено: Загрузки/Phoenix") else tr("Не удалось сохранить файл"), Toast.LENGTH_SHORT).show()
                }) { Text(tr("В файл")) }
            }
        }
        if (visible.isEmpty()) {
            Text(
                if (entries.isEmpty()) tr("Серверов ещё нет. Нажмите «Собрать сейчас» на главном экране.") else tr("Под фильтры ничего не подходит."),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visible, key = { it.raw }) { e ->
                Card(Modifier.fillMaxWidth().clickable { Exporter.copy(context, e.raw, 1) }) {
                    Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
                        Text((if (e.stable) "★ " else "") + e.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(ServerTable.metrics(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.stale) {
                            Text(tr("Адрес сменился после проверки: данные устарели"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            TextButton(onClick = { byRaw[e.raw]?.let(onConnect) }) { Text(tr("Подключить")) }
                            TextButton(onClick = { vm.runChecks(listOf(e.raw)) }) { Text(tr("Проверить")) }
                            TextButton(onClick = { vm.measureSpeed(e.raw) }) { Text(tr("Скорость")) }
                        }
                    }
                }
            }
        }
    }
}
