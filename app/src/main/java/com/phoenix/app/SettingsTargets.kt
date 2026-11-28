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

@Composable
fun TargetsPage(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val targets by vm.targets.collectAsStateWithLifecycle()
    val global by vm.globalPolicy.collectAsStateWithLifecycle()
    val modeNow by vm.mode.collectAsStateWithLifecycle()
    val separateNow by vm.separate.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.checkStatus.collectAsStateWithLifecycle()
    var showApps by remember { mutableStateOf(false) }
    var showSite by remember { mutableStateOf(false) }
    var pendingApp by remember { mutableStateOf<InstalledApp?>(null) }
    var editUrl by remember { mutableStateOf<Target?>(null) }
    var usage by remember { mutableStateOf(ForegroundWatcher.hasAccess(context)) }

    LaunchedEffect(Unit) { vm.reloadTargets() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) usage = ForegroundWatcher.hasAccess(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item { Text(tr("Что делать, если приложение не работает на текущем сервере"), style = MaterialTheme.typography.titleMedium) }
        item {
            Hint(tr("Для каждой цели приложение держит до 3 проверенных серверов и перепроверяет их раз в несколько часов."))
        }
        item {
            Hint(tr("У каждой цели есть адрес проверки: по нему видно, открывается ли сервис через сервер. Для известных приложений он подставляется сам, для остальных проверяется просто работа сервера; адрес можно поменять кнопкой «Адрес»."))
        }
        item {
            FilterChip(selected = global == Policy.AUTO, onClick = { vm.setGlobalPolicy(Policy.AUTO) }, label = { Text(tr("Само выбирает (рекомендуется)")) })
        }
        item {
            FilterChip(selected = global == Policy.PROXY, onClick = { vm.setGlobalPolicy(Policy.PROXY) }, label = { Text(tr("А: только оно через свой сервер")) })
        }
        item {
            FilterChip(selected = global == Policy.SWITCH, onClick = { vm.setGlobalPolicy(Policy.SWITCH) }, label = { Text(tr("Б: переключить весь VPN")) })
        }
        // Н8: запись идёт в настройки активного режима, если у него они свои; без приписки этого не видно
        if (separateNow[modeNow] == true) item { Hint(tr("Для режима «{}» (у него свои настройки)", tr(modeNow.title))) }
        if (global == Policy.AUTO) {
            item {
                Hint(
                    tr("«как везде» у приложения значит: взять выбор, сделанный выше, для всех. Остальные кнопки (авто, «Только это», «Весь VPN») задают своё для этого приложения.\n\nСайты — всегда в режиме «Только это». Приложение — «Только это», если ядро умеет узнавать приложение по соединению (тогда остальной трафик не затрагивается); если не умеет — «Весь VPN», когда выдан доступ к данным об использовании.")
                )
            }
        }
        if (global == Policy.AUTO || global == Policy.SWITCH || targets.any { it.policy == Policy.SWITCH || it.policy == Policy.AUTO }) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (usage) tr("Доступ к данным об использовании: есть") else tr("Для режима «Весь VPN» нужен доступ к данным об использовании"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(tr("Чтобы узнать, какое приложение открыто сейчас. Данные остаются на телефоне."))
                        if (!usage) TextButton(onClick = { ForegroundWatcher.openAccessSettings(context) }) { Text(tr("Открыть настройки")) }
                    }
                }
            }
        }
        item {
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showApps = true }) { Text(tr("+ Приложение")) }
                OutlinedButton(onClick = { showSite = true }) { Text(tr("+ Сайт")) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.verifyTargetsNow() }, enabled = busy.isEmpty() && targets.isNotEmpty()) { Text(tr("Проверить серверы сейчас")) }
            }
        }
        if (busy.isNotEmpty()) item { Text(busy, style = MaterialTheme.typography.bodySmall) }
        else if (status.isNotBlank()) item { Text(status, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(8.dp)) }
        if (targets.isEmpty()) item { Hint(tr("Целей пока нет. Добавьте приложение (список берётся с телефона) или сайт.")) }
        items(targets, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (t.kind == TargetKind.APP) "📱 " else "🌐 ") + t.label, style = MaterialTheme.typography.titleSmall)
                            Hint(
                                if (t.isDirect) tr("напрямую, мимо VPN (сервер не нужен)")
                                else if (t.servers.isEmpty()) tr("нет своего сервера — идёт через общий")
                                else tr("серверов: {}", t.servers.size) + if (t.verifiedAt.isNotBlank()) tr(" · проверено {}", t.verifiedAt) else "",
                            )
                        }
                        TextButton(onClick = { editUrl = t }) { Text(tr("Адрес")) }
                        var askRemove by remember(t.id) { mutableStateOf(false) }
                        TextButton(onClick = { askRemove = true }) { Text(tr("Убрать")) }
                        if (askRemove) ConfirmDialog(tr("Убрать цель?"), tr("Цель «{}» и её серверы будут удалены.", t.label), onYes = { vm.removeTarget(t.id) }, onDismiss = { askRemove = false })
                    }
                    Hint(tr("проверка: ") + t.checkUrl.removePrefix("https://").removePrefix("http://") +
                        if (t.kind == TargetKind.APP && t.checkUrl == KnownApps.DEFAULT_URL) tr(" (общая: только работа сервера)") else "")
                    if (t.kind == TargetKind.APP) {
                        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = t.policy == Policy.DEFAULT, onClick = { vm.setTargetPolicy(t, Policy.DEFAULT) }, label = { Text(tr("как везде")) })
                            FilterChip(selected = t.policy == Policy.AUTO, onClick = { vm.setTargetPolicy(t, Policy.AUTO) }, label = { Text(tr("авто")) })
                            FilterChip(selected = t.policy == Policy.PROXY, onClick = { vm.setTargetPolicy(t, Policy.PROXY) }, label = { Text(tr("Только это")) })
                            FilterChip(selected = t.policy == Policy.SWITCH, onClick = { vm.setTargetPolicy(t, Policy.SWITCH) }, label = { Text(tr("Весь VPN")) })
                        }
                    } else {
                        Hint(tr("Для сайта работает только режим «Только это»."))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showApps) {
        SingleAppPicker(vm, onDismiss = { showApps = false }) { app ->
            showApps = false
            pendingApp = app
        }
    }
    pendingApp?.let { app ->
        var url by remember(app.pkg) { mutableStateOf(KnownApps.urlOrDefault(app.pkg)) }
        AlertDialog(
            onDismissRequest = { pendingApp = null },
            title = { Text(app.label) },
            text = {
                Column {
                    Hint(
                        if (KnownApps.isKnown(app.pkg)) tr("Адрес, который открывает это приложение: через него проверяется, работает ли сервер для него.")
                        else tr("Адрес этого приложения не известен, подставлена общая проверка (работает ли сервер вообще). Если знаете, какой сайт использует приложение, впишите его адрес — проверка станет точнее.")
                    )
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("https://…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = vm.addTargetApp(app.pkg, app.label, url)
                    if (err == null) pendingApp = null
                    toast(err ?: tr("Добавлено"))
                }) { Text(tr("Добавить")) }
            },
            dismissButton = { TextButton(onClick = { pendingApp = null }) { Text(tr("Отмена")) } },
        )
    }
    editUrl?.let { t ->
        var url by remember(t.id) { mutableStateOf(t.checkUrl) }
        AlertDialog(
            onDismissRequest = { editUrl = null },
            title = { Text(t.label) },
            text = {
                Column {
                    Hint(tr("Адрес, по которому проверяется, что сервер работает для этой цели. Пустое поле вернёт адрес по умолчанию. Серверы пересчитаются при ближайшей проверке."))
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("https://…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = vm.setTargetUrl(t, url)
                    if (err == null) editUrl = null
                    toast(err ?: tr("Сохранено"))
                }) { Text(tr("Сохранить")) }
            },
            dismissButton = { TextButton(onClick = { editUrl = null }) { Text(tr("Отмена")) } },
        )
    }
    if (showSite) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSite = false },
            title = { Text(tr("Сайт")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(tr("example.com или ссылка")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = vm.addTargetSite(text)
                    if (err == null) showSite = false
                    toast(err ?: tr("Добавлено"))
                }) { Text(tr("Добавить")) }
            },
            dismissButton = { TextButton(onClick = { showSite = false }) { Text(tr("Отмена")) } },
        )
    }
}

@Composable
private fun SingleAppPicker(vm: MainViewModel, onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { vm.installedApps() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Выберите приложение")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text(tr("Поиск")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Text(tr("Загружаю список…"), modifier = Modifier.padding(top = 8.dp))
                } else {
                    val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                    LazyColumn(Modifier.height(360.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

/** Сайты, которые открываются напрямую, мимо VPN (российские уже включены по умолчанию). */
@Composable
fun BypassSitesSection(vm: MainViewModel) {
    val context = LocalContext.current
    val sites by vm.bypassSites.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.reloadTargets() }

    Text(tr("Сайты в обход VPN"), style = MaterialTheme.typography.titleMedium)
    Hint(
        tr("Российские сайты ({} доменов: .ru, .рф, Госуслуги, банки, Яндекс, ВК…) открываются напрямую по умолчанию. Здесь можно добавить свои. Применится при следующем подключении.", RussianSites.DOMAINS.size)
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("example.com") },
            singleLine = true, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val err = vm.addBypassSite(text)
            if (err == null) text = ""
            Toast.makeText(context, err ?: tr("Добавлено"), Toast.LENGTH_SHORT).show()
        }) { Text(tr("Добавить")) }
    }
    sites.forEach { host ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(host, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.removeBypassSite(host) }) { Text(tr("Убрать")) }
        }
    }
}


// ---------------------------------------------------------------- Журнал
