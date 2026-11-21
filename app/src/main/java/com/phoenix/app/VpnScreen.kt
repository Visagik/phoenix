@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.phoenix.app

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Вкладка VPN: состояние ядра, проверка сервисов, обход VPN приложениями. Данные берутся из текущего режима. */
@Composable
fun VpnTab(vm: MainViewModel, onOpenServices: () -> Unit = {}) {
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val selected by vm.selectedServices.collectAsStateWithLifecycle()
    val status by vm.checkStatus.collectAsStateWithLifecycle()
    val userApps by vm.bypassUser.collectAsStateWithLifecycle()
    val presetOn by vm.presetOn.collectAsStateWithLifecycle()
    val found by vm.bypassFound.collectAsStateWithLifecycle()
    val openOn by vm.openOn.collectAsStateWithLifecycle()
    val appLinksOn by vm.appLinksOn.collectAsStateWithLifecycle()
    val hevOn by vm.hevOn.collectAsStateWithLifecycle()
    val custom by vm.customServices.collectAsStateWithLifecycle()
    val allServices by vm.allServices.collectAsStateWithLifecycle()
    val hiddenCount by vm.hiddenCount.collectAsStateWithLifecycle()
    val autoServices by vm.autoServices.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val appSet = remember { AppSettings(context) }
    var appsMode by remember { mutableStateOf(appSet.appsMode) }
    var onlyApps by remember { mutableStateOf(AppsMode.decode(appSet.onlyApps)) }
    var showOnlyPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Какие приложения идут через VPN"), style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(AppsMode.ALL_EXCEPT to tr("Все, кроме списка"), AppsMode.ONLY_SELECTED to tr("Только выбранные")).forEach { (v, label) ->
                FilterChip(selected = appsMode == v, onClick = {
                    appsMode = v; appSet.appsMode = v; PhoenixVpnService.applyBypass(context)
                }, label = { Text(label) })
            }
        }
        Text(
            if (appsMode == AppsMode.ONLY_SELECTED) tr("Через VPN идут только выбранные приложения (как белый список) и приложения из «Свой сервер для приложений»; всё остальное напрямую. Сайты идут через VPN, только если через него идёт браузер.")
            else tr("Через VPN идёт всё, кроме приложений из списка ниже (как чёрный список)."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (appsMode == AppsMode.ONLY_SELECTED) {
            OutlinedButton(onClick = { showOnlyPicker = true }) { Text(tr("Приложения через VPN ({})", onlyApps.size)) }
            if (onlyApps.isEmpty()) {
                Text(tr("Список пуст: пока он пуст, работает режим «Все, кроме списка»."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            onlyApps.sorted().forEach { pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appLabel(LocalContext.current, pkg), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        onlyApps = onlyApps - pkg; appSet.onlyApps = AppsMode.encode(onlyApps); PhoenixVpnService.applyBypass(context)
                    }) { Text(tr("Убрать")) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (appsMode == AppsMode.ALL_EXCEPT) {
            Text(tr("Приложения в обход VPN ({})", tr(mode.title)), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Эти приложения работают напрямую, мимо VPN. У каждого режима свой список. Изменения применяются при следующем подключении."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                tr("Российские приложения"),
                tr("Банки, Госуслуги, ВК, Яндекс, маркетплейсы, операторы ({} шт., берутся только установленные).", RussianApps.PACKAGES.size),
                presetOn, onChange = { vm.setPreset(it) },
            )
            SwitchRow(
                tr("Не заблокированное в РФ"),
                tr("Steam, GitHub, Wikipedia, Microsoft, Apple, Amazon, AliExpress, Zoom, Twitch и подобные открываются напрямую: быстрее и без лишнего трафика. Telegram, YouTube, Instagram, Discord, ChatGPT, Claude и всё, что в России режется, остаётся в VPN. ") +
                    if (mode == Mode.BLACKLIST) tr("Для чёрных списков включено по умолчанию.") else tr("Для белых списков по умолчанию выключено: напрямую там открывается только разрешённое."),
                openOn, onChange = { vm.setOpenPreset(it) },
            )
            SwitchRow(
                tr("Сайты этих приложений тоже напрямую"),
                tr("Android сам знает, какие ссылки открывает приложение (например, сайт банка). Эти сайты в браузере тоже идут мимо VPN, как и само приложение. Работает на Android 12 и новее; Telegram, YouTube, Google и подобные сюда не попадают никогда."),
                appLinksOn, onChange = { vm.setAppLinks(it) },
            )
        }
        SwitchRow(
            tr("Быстрый движок туннеля (hev)"),
            tr("Тот же движок, что у v2rayNG: обычно быстрее на скорости. Вступает в силу при следующем подключении. Только 64-битные телефоны; если у вас есть сайты или приложения со своим сервером, по приложениям они работать не будут — тогда автоматически остаётся встроенный.") +
                if (!vm.hevAvailable) tr(" Сейчас недоступен на этом телефоне.") else "",
            hevOn, enabled = vm.hevAvailable, onChange = { vm.setHev(it) },
        )
        if (appsMode == AppsMode.ALL_EXCEPT) {
            if (found.isNotEmpty()) {
                Text(
                    tr("Найдено автоматически ({}): приложения с российскими пакетами, которых нет в наборе. Если приложению нужен VPN, уберите его.", found.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                found.forEach { pkg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(appLabel(LocalContext.current, pkg), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { vm.excludeFoundApp(pkg) }) { Text(tr("Убрать")) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showPicker = true }) { Text(tr("Свои приложения ({})", userApps.size)) }
            userApps.forEach { pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appLabel(LocalContext.current, pkg), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { vm.setBypassApps(userApps - pkg) }) { Text(tr("Убрать")) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.height(12.dp))
        BypassSitesSection(vm)

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenServices) { Text(tr("Проверяемые сервисы перенесены: Ещё → Серверы ›")) }
    }

    if (showOnlyPicker) {
        AppPickerDialog(
            vm = vm,
            initial = onlyApps,
            title = tr("Приложения через VPN"),
            onDismiss = { showOnlyPicker = false },
            onConfirm = { chosen ->
                onlyApps = chosen
                appSet.onlyApps = AppsMode.encode(chosen)
                showOnlyPicker = false
                PhoenixVpnService.applyBypass(context)
                Toast.makeText(context, tr("Список сохранён"), Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showPicker) {
        AppPickerDialog(
            vm = vm,
            initial = userApps,
            onDismiss = { showPicker = false },
            onConfirm = { chosen ->
                vm.setBypassApps(chosen)
                showPicker = false
                Toast.makeText(context, tr("Список обхода сохранён"), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

private const val CHECK_LIMIT = 30

/** «Проверяемые сервисы»: какие сервисы открывать через рабочие серверы (TG, YT…), свои сервисы, проверка по кнопке. Раньше был внутри «Мимо VPN». */
@Composable
fun ServicesPage(vm: MainViewModel) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val selected by vm.selectedServices.collectAsStateWithLifecycle()
    val status by vm.checkStatus.collectAsStateWithLifecycle()
    val custom by vm.customServices.collectAsStateWithLifecycle()
    val allServices by vm.allServices.collectAsStateWithLifecycle()
    val hiddenCount by vm.hiddenCount.collectAsStateWithLifecycle()
    val autoServices by vm.autoServices.collectAsStateWithLifecycle()
    var showAddService by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.height(12.dp))
        Text(tr("Проверка сервисов через конфиг"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Ядро открывает выбранные сервисы через рабочие конфиги и записывает результат к каждому конфигу (в карточке: «Проверено»; лучшие конфиги — те, через которые открывается больше сервисов). Сайты с защитой от ботов (Claude, Qwen) отвечают отказом, но это тоже считается «дотянулись»."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            tr("Сервисы берутся из ваших целей: цель добавили — появилась колонка, убрали — исчезла. Добавлять отдельные сервисы не нужно."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            allServices.forEach { s ->
                AssistChip(onClick = {}, label = { Text(s.short + " · " + s.title, maxLines = 1) })
            }
        }
        Text(
            tr("✓ открылся · ≈ отвечает, но не полностью (защита сайта; для Claude и Qwen это обычно норма) · ✗ не открылся · ? не проверялся"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hiddenCount > 0) TextButton(onClick = { vm.restoreServices() }) { Text(tr("Вернуть убранные ({})", hiddenCount)) }
        custom.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${s.id} · ${s.title} — ${s.url}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { vm.removeCustomService(s.id) }) { Text(tr("Убрать")) }
            }
        }
        SwitchRow(
            tr("Проверять рабочие конфиги само"),
            tr("После перепроверки и в фоне: у рабочих конфигов, которых ещё не проверяли по выбранным сервисам, проверяются они. Добавили сервис — проверятся и уже проверенные."),
            autoServices, onChange = { vm.setAutoServices(it) },
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { vm.runChecks(rows.take(CHECK_LIMIT).map { it.raw }) }, enabled = rows.isNotEmpty()) {
            Text(tr("Проверить первые {} конфигов", minOf(rows.size, CHECK_LIMIT)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    if (showAddService) {
        var title by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddService = false },
            title = { Text(tr("Свой сервис")) },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(tr("Название")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text(tr("Адрес (например, https://discord.com/)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = vm.addCustomService(title, address)
                    if (err == null) showAddService = false else error = err
                }) { Text(tr("Добавить")) }
            },
            dismissButton = { TextButton(onClick = { showAddService = false }) { Text(tr("Отмена")) } },
        )
    }
}

@Composable
private fun AppPickerDialog(vm: MainViewModel, initial: Set<String>, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit, title: String = tr("Приложения в обход VPN")) {
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { vm.installedApps() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Поиск")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Text(tr("Загружаю список…"), modifier = Modifier.padding(top = 8.dp))
                } else {
                    val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                    LazyColumn(Modifier.height(360.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = app.pkg in chosen, onCheckedChange = {
                                    chosen = if (it) chosen + app.pkg else chosen - app.pkg
                                })
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.pkg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}
