@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun toEntry(r: ConfigRow, c: CheckResult?, sel: List<CheckService>): ServerEntry {
    val fresh = c != null && !c.staleFor(r.raw)
    val live = c != null && fresh && !c.dead
    var ok = 0
    var part = 0
    var bl = 0
    var unk = 0
    if (live && c != null) {
        for (s in sel) {
            val ms = c.ms(s)
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

/** Ключ проверки по ссылке: разбор имени и SHA-1 — дорогие, одну и ту же ссылку не разбираем повторно. */
private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()
private fun keyOf(raw: String): String {
    if (keyCache.size > 6000) keyCache.clear()
    return keyCache.getOrPut(raw) { CheckStore.keyOf(raw) }
}

/** Готовый к показу список «Серверов»: считается на фоновом потоке. */
private class ServersModel(
    val entries: List<ServerEntry>,
    val visible: List<ServerEntry>,
    val countries: List<String>,
    val protocols: List<String>,
    val summary: String,
) {
    companion object {
        val EMPTY = ServersModel(emptyList(), emptyList(), emptyList(), emptyList(), "")

        fun build(
            rows: List<ConfigRow>, checks: Map<String, CheckResult>, selected: List<CheckService>,
            filter: ServerFilter, sort: ServerSort, desc: Boolean?,
        ): ServersModel {
            val entries = rows.map { r ->
                val e = toEntry(r, checks[keyOf(r.raw)], selected)
                e.copy(metricsText = ServerTable.metrics(e))
            }
            return ServersModel(
                entries,
                ServerTable.apply(entries, filter, sort, desc),
                ServerTable.countries(entries).take(12),
                ServerTable.protocols(entries),
                ServerTable.summary(entries),
            )
        }
    }
}

/** Строка прогресса перепроверки и сводки: подписка на прогресс здесь, а не на всём экране. */
@Composable
private fun RecheckLine(rechecking: Boolean, summary: String) {
    val progress by RecheckState.progress.collectAsStateWithLifecycle()
    Text(
        (if (rechecking) "⏳ " + progress.ifBlank { tr("Перепроверяю…") } + "\n" else "") + summary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Окно «Серверы»: все показатели, фильтры и сортировка. Список с главного экрана перенесён сюда. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersPage(vm: MainViewModel, onConnect: (ConfigRow) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MemInfo.log(context, "открыт список «Серверы»") } }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val selected by vm.selectedServices.collectAsStateWithLifecycle()
    val rechecking by RecheckState.running.collectAsStateWithLifecycle()

    var sort by rememberSaveable { mutableStateOf(ServerSort.BEST) }
    var desc by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var filter by rememberSaveable { mutableStateOf(ServerFilter()) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var openRaw by rememberSaveable { mutableStateOf("") }

    val byRaw = remember(rows) { rows.associateBy { it.raw } }
    // текущий и закреплённый сервер: выделяются в списке
    val vpnNow by VpnState.status.collectAsStateWithLifecycle()
    val pinnedNow by VpnState.pinned.collectAsStateWithLifecycle()
    val currentId = remember(vpnNow.link, vpnNow.phase) { if (vpnNow.phase == VpnPhase.CONNECTED && vpnNow.link.isNotBlank()) ConfigIdentity.key(vpnNow.link) else "" }
    // разбор ссылок, сортировка и фильтры — не в основном потоке (1500 строк: иначе прокрутка и ввод в поиске подвисают);
    // при смене данных прежний расчёт отменяется, на экране остаётся предыдущий результат
    val model by produceState(ServersModel.EMPTY, rows, checks, selected, filter, sort, desc) {
        value = withContext(Dispatchers.Default) { ServersModel.build(rows, checks, selected, filter, sort, desc) }
    }
    val entries = model.entries
    val visible = model.visible
    // Lite: на слабом планшете в списке не больше RECHECK_MAX строк, остальные — по кнопке
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) visible else LiteProfile.capRecheck(visible)
    val countries = model.countries
    val protocols = model.protocols

    Column(Modifier.fillMaxSize()) {
        // шапка (сортировка, фильтры, поиск, кнопки) — первым элементом списка: прокручивается вместе с ним и не съедает экран
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "header") {
                Column {
            Column(Modifier.padding(vertical = 8.dp)) {
                // прогресс читается в отдельной функции: его обновление не перестраивает весь экран
                var menu by remember { mutableStateOf(false) }
                val collecting by RunState.running.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { RecheckLine(rechecking, ServerTable.shortSummary(entries, visible.size)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = tr("Ещё")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(tr("Собрать сейчас")) }, enabled = !collecting, onClick = { menu = false; vm.runNow() })
                            DropdownMenuItem(text = { Text(if (rechecking) tr("Проверяю…") else tr("Перепроверить всё")) }, enabled = !rechecking, onClick = { menu = false; vm.recheckAll() })
                            DropdownMenuItem(text = { Text(tr("Только приложения")) }, enabled = !rechecking, onClick = { menu = false; vm.recheckApps() })
                            DropdownMenuItem(text = { Text(tr("Копировать ({})", visible.size)) }, enabled = visible.isNotEmpty(), onClick = { menu = false; Exporter.copy(context, visible.joinToString("\n") { it.raw }, visible.size) })
                            DropdownMenuItem(text = { Text("V2RayNG") }, onClick = { menu = false; Exporter.openV2RayNG(context) })
                            DropdownMenuItem(text = { Text(tr("В файл")) }, onClick = {
                                menu = false
                                val text = vm.enrichedText()
                                val ok = text.isNotEmpty() && Exporter.saveToDownloads(context, Storage.ENRICHED_NAME, text)
                                Toast.makeText(context, if (ok) tr("Сохранено: {}", Exporter.where(context)) else tr("Не удалось сохранить файл"), Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { filter = filter.copy(query = it) },
                    label = { Text(tr("Поиск по имени или стране")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                // быстрые фильтры строкой; остальное — в нижнем листе («Фильтры»), чтобы список был сразу под полем поиска
                val extra = listOf(filter.onlyChecked, filter.onlyStable, filter.minOk >= 3, filter.protocol.isNotEmpty(), filter.country.isNotEmpty()).count { it }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter.onlyAlive, onClick = { filter = filter.copy(onlyAlive = true) }, label = { Text(tr("Живые")) })
                    FilterChip(selected = !filter.onlyAlive, onClick = { filter = filter.copy(onlyAlive = false) }, label = { Text(tr("Все")) })
                    FilterChip(selected = extra > 0, onClick = { showTools = true }, label = { Text(if (extra > 0) tr("Фильтры: {}", extra) else tr("Фильтры и сортировка")) })
                }
            }
            if (visible.isEmpty()) {
                Text(
                    if (entries.isEmpty()) tr("Серверов пока нет")  else tr("Под фильтры ничего не подходит."),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entries.isNotEmpty()) TextButton(onClick = { filter = ServerFilter() }) { Text(tr("Сбросить")) }
                else TextButton(onClick = { vm.runNow() }) { Text(tr("Собрать")) }
            }
                }
            }
            if (shown.size < visible.size) item {
                TextButton(onClick = { showAll = true }) { Text(tr("Показано {} из {}. Показать все", shown.size, visible.size)) }
            }
            items(shown, key = { it.raw }, contentType = { "server" }) { e ->
                val open = openRaw == e.raw
                val isCurrent = currentId.isNotEmpty() && remember(e.raw) { ConfigIdentity.key(e.raw) } == currentId
                val isPinned = isCurrent && pinnedNow
                val scheme = MaterialTheme.colorScheme
                val dark = appDark()
                val tone = ServerTable.latencyTone(e)
                val pingColor = when (tone) { 0 -> PhoenixTheme.ok(dark); 1 -> PhoenixTheme.amber(dark); else -> scheme.onSurfaceVariant }
                Card(
                    Modifier.fillMaxWidth().then(if (e.dead && !isCurrent) Modifier.alpha(0.55f) else Modifier).clickable { openRaw = if (open) "" else e.raw },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isCurrent) scheme.secondaryContainer else scheme.surfaceContainer),
                    border = if (isPinned) BorderStroke(1.dp, scheme.primary) else null,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        // свёрнуто: кружок страны, имя и тип слева, задержка справа; подробности и кнопки — у раскрытой строки
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                Text((e.cc ?: "").takeIf { it.length == 2 } ?: "–", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(DisplayName.plain(e.name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(ServerTable.subtitle(e), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(ServerTable.latencyText(e), style = MaterialTheme.typography.titleMedium, color = pingColor, maxLines = 1)
                                if (isCurrent) Text(if (isPinned) tr("закреплён") else tr("сейчас"), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                            }
                        }
                        if (open) {
                            Spacer(Modifier.height(8.dp))
                            Text(e.metricsText.ifEmpty { ServerTable.metrics(e) }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        if (e.stale) {
                            Text(tr("Адрес сменился после проверки: данные устарели"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (open) {
                            FlowRow {
                                TextButton(onClick = { byRaw[e.raw]?.let(onConnect) }) { Text(tr("Подключить")) }
                                TextButton(onClick = { Exporter.copy(context, e.raw, 1) }) { Text(tr("Копировать")) }
                                TextButton(onClick = { vm.runChecks(listOf(e.raw)) }) { Text(tr("Проверить")) }
                                TextButton(onClick = { vm.measureSpeed(e.raw) }) { Text(tr("Скорость")) }
                                TextButton(onClick = {
                                    PoolPolicyStore.hide(context, ConfigIdentity.key(e.raw))
                                    UiMessages.post(tr("Сервер скрыт. Вернуть: Ещё → Страны и серверы"))
                                }) { Text(tr("Не использовать")) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showTools) {
        ModalBottomSheet(onDismissRequest = { showTools = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                TextButton(onClick = { filter = ServerFilter(query = filter.query) }) { Text(tr("Сбросить фильтры")) }
            }
        }
    }
}
