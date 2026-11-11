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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.withContext

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Режим блокировок

@Composable
fun ModePage(vm: MainViewModel) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val auto by vm.autoMode.collectAsStateWithLifecycle()
    val detect by vm.detectText.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Тип блокировок"), style = MaterialTheme.typography.titleMedium)
        Hint(
            tr("Белые списки: открываются только российские сайты. Чёрные: закрыты отдельные сайты. У каждого режима свои источники, статистика и список обхода. Ночной сбор идёт для каждого режима, у которого есть источники.")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Mode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { vm.setMode(m) }, label = { Text(tr(m.title)) })
            }
        }
        SwitchRow(
            tr("Определять сам"),
            tr("Приложение проверяет, какие сайты открываются напрямую, и переключает режим, когда два определения подряд совпали. Проверка идёт при смене сети."),
            auto,
        ) { vm.setAutoMode(it) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.detectNow() }, enabled = busy.isEmpty()) { Text(tr("Определить сейчас")) }
        if (busy.isNotEmpty()) Text(busy, style = MaterialTheme.typography.bodySmall)
        if (detect.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Последнее определение: {}", detect), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- Подключение

@Composable
fun ConnectionPage(vm: MainViewModel) {
    val context = LocalContext.current
    val reconnect by vm.autoReconnect.collectAsStateWithLifecycle()
    val metered by vm.speedOnMetered.collectAsStateWithLifecycle()
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val ipv6 by vm.ipv6.collectAsStateWithLifecycle()
    val kill by vm.killSwitch.collectAsStateWithLifecycle()
    val slowOn by vm.slowSwitch.collectAsStateWithLifecycle()
    val slowMin by vm.slowMin.collectAsStateWithLifecycle()
    val quic by vm.blockQuic.collectAsStateWithLifecycle()
    val autoRecheck by vm.autoRecheck.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val separate by vm.separate.collectAsStateWithLifecycle()

    // На этом экране правится выбранная область; уходя с него, возвращаем «действующие настройки активного режима».
    DisposableEffect(Unit) {
        vm.setScope(AppSettings.Scope.COMMON)
        onDispose { vm.setScope(AppSettings.Scope.AUTO) }
    }
    val editMode: Mode? = when (scope) {
        AppSettings.Scope.WHITELIST -> Mode.WHITELIST
        AppSettings.Scope.BLACKLIST -> Mode.BLACKLIST
        else -> null
    }
    val editable = editMode == null || separate[editMode] == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Ядро"), style = MaterialTheme.typography.titleMedium)
        Text(
            if (XrayCore.available) tr("Xray: версия {}", XrayCore.version()) else XrayCore.problem + if (XrayCore.present) tr(" Сейчас: {}.", XrayCore.version()) else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (XrayCore.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        var net by remember { mutableStateOf(NetInfo.kind(context)) }
        LaunchedEffect(Unit) { net = NetInfo.kind(context) }
        Text(tr("Сеть сейчас: {}", tr(net.title)), style = MaterialTheme.typography.bodyMedium)
        Hint(
            if (NetInfo.isUnmetered(context)) tr("Замеры скорости идут сами.")
            else tr("Замеры скорости сами не идут (мобильная сеть); кнопка «Скорость» на карточке работает всегда.")
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(tr("Чьи настройки править"), style = MaterialTheme.typography.titleMedium)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = scope == AppSettings.Scope.COMMON, onClick = { vm.setScope(AppSettings.Scope.COMMON) }, label = { Text(tr("Общие")) })
            FilterChip(selected = scope == AppSettings.Scope.WHITELIST, onClick = { vm.setScope(AppSettings.Scope.WHITELIST) }, label = { Text(tr("Белые")) })
            FilterChip(selected = scope == AppSettings.Scope.BLACKLIST, onClick = { vm.setScope(AppSettings.Scope.BLACKLIST) }, label = { Text(tr("Чёрные")) })
        }
        Hint(
            if (editMode == null)
                tr("Общие значения действуют в каждом режиме, у которого нет своих настроек. У режима можно включить свои: тогда он живёт по ним, а общие его не трогают.")
            else
                tr("Режим «{}»: когда он действует, берутся его настройки (если включены свои), иначе общие.", tr(editMode.title))
        )
        if (editMode != null) {
            SwitchRow(
                tr("Свои настройки для «{}»", tr(editMode.title)),
                tr("При включении сюда копируются текущие общие значения, дальше их можно менять независимо."),
                separate[editMode] == true,
            ) { vm.setSeparate(editMode, it) }
            if (separate[editMode] == true) {
                val ovr = remember(scope, separate, reconnect, metered, autoConnect, ipv6, kill, slowOn, slowMin, quic, autoRecheck) { vm.overridden(editMode) }
                Text(
                    if (ovr.isEmpty()) tr("Отличий от общих настроек нет.") else tr("Отличается от общих ({}): ", ovr.size) + ovr.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ovr.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                OutlinedButton(onClick = { vm.resetOverrides(editMode) }) { Text(tr("Сбросить к общим настройкам")) }
            }
        }
        HorizontalDivider()
        if (!editable) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Сейчас для этого режима действуют общие настройки. Включите «Свои настройки», чтобы менять их отдельно."), style = MaterialTheme.typography.bodyMedium)
        }
        if (editable) {
        SwitchRow(
            tr("Переподключаться само"),
            tr("Если туннель перестал работать (три неудачные проверки), приложение перебирает запасные конфиги лучшего рейтинга."),
            reconnect,
        ) { vm.setAutoReconnect(it) }
        SwitchRow(
            tr("Менять сервер при низкой скорости"),
            tr("Бывает, что интернет «есть», но фото и страницы почти не грузятся. Пока вы пользуетесь телефоном, раз в 45 секунд смотрится, сколько данных идёт через сервер; если мало, делается один маленький замер (до 400 КБ), и после двух медленных подряд пробуются другие серверы. Если телефон не используется — ничего не качается."),
            slowOn,
        ) { vm.setSlowSwitch(it) }
        if (slowOn) {
            Hint(tr("Считать сервер медленным, если скорость ниже (Мбит/с):"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.3f, 0.5f, 1.0f, 2.0f).forEach { v ->
                    FilterChip(
                        selected = kotlin.math.abs(slowMin - v) < 0.01f,
                        onClick = { vm.setSlowMin(v) },
                        label = { Text("%.1f".format(java.util.Locale.US, v)) },
                    )
                }
            }
            Hint(tr("Не чаще раза в 5 минут и не больше 4 раз в час, чтобы при плохой сети везде не прыгать с сервера на сервер."))
        }
        SwitchRow(
            tr("Измерять скорость и по мобильной сети"),
            tr("Замер скачивает до 8 МБ. По умолчанию — только по Wi-Fi."),
            metered,
        ) { vm.setSpeedOnMetered(it) }
        SwitchRow(
            tr("Закрыть QUIC (UDP 443)"),
            tr("Многие серверы не пропускают UDP: приложения ждут ответа и не открываются, хотя Telegram работает. При закрытом QUIC они сразу переходят на обычный TCP. Рекомендуется оставить включённым. Применяется при следующем подключении."),
            quic,
        ) { vm.setBlockQuic(it) }
        SwitchRow(
            tr("Сам перепроверять все конфиги"),
            tr("Если запасной сервер не нашёлся, приложение само запускает полную перепроверку всех конфигов (не чаще раза в 30 минут)."),
            autoRecheck,
        ) { vm.setAutoRecheck(it) }
        // тихие часы: тяжёлое (перепроверка, аудит) без зарядки не запускается
        val quietSettings = remember { AppSettings(context) }
        var focus by remember { mutableStateOf(quietSettings.focusMode) }
        SwitchRow(
            tr("Режим «Фокус»"),
            tr("Пока открыто приложение из ваших целей (игра, банк, звонок), приложение не запускает тяжёлые проверки и не меняет сервер по скорости. Нужен доступ к данным об использовании."),
            focus,
        ) { focus = it; quietSettings.focusMode = it }
        var appAware by remember { mutableStateOf(quietSettings.appAwareFailover) }
        SwitchRow(
            tr("Смена сервера с учётом открытого приложения"),
            tr("Когда сервер перестаёт работать и приложение само ищет другой, оно смотрит, какое приложение открыто сейчас (нужен доступ к данным об использовании), и пробует первыми серверы, на которых это приложение уже открывалось. Если у приложения есть своя цель — сначала её серверы."),
            appAware,
        ) { appAware = it; quietSettings.appAwareFailover = it }
        val siteWatching = CurrentSite.isListed(
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            context.packageName, "SiteWatcherService",
        )
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(if (siteWatching) tr("Сайт в браузере: определяется") else tr("Сайт в браузере: не определяется"), style = MaterialTheme.typography.bodyMedium)
                Hint(
                    tr("Если включить службу специальных возможностей «Phoenix: сайт в браузере», приложение видит только домен открытого сайта (адресная строка Chrome, Яндекс, Samsung, Firefox, Opera, Edge, Brave и ещё нескольких браузеров) и при смене сервера подбирает тот, где этот сайт открывается. Домен хранится только в памяти, содержимое страниц не читается, ничего не отправляется. На Android 13 и новее для приложения не из магазина сначала откройте «О приложении» → меню ⋮ → «Разрешить ограниченные настройки»."),
                )
                Row {
                    TextButton(onClick = { try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }) { Text(tr("Специальные возможности")) }
                    TextButton(onClick = { Background.openAppSettings(context) }) { Text(tr("О приложении")) }
                }
            }
        }
        var geoUrl by remember { mutableStateOf(quietSettings.geoRulesUrl) }
        OutlinedTextField(
            value = geoUrl,
            onValueChange = { geoUrl = it; quietSettings.geoRulesUrl = it },
            label = { Text(tr("Ссылка на правила регионов (необязательно)")) },
            supportingText = { Text(tr("Файл со строками host:, closed:, marker:… Если пусто или файл не подходит, действуют встроенные правила: какие сайты закрыты по стране выхода и по каким фразам узнаётся страница «не поддерживается в вашей стране». Обновляется при запуске приложения.")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        var quietAuto by remember { mutableStateOf(quietSettings.quietAuto) }
        var quietManual by remember { mutableStateOf(quietSettings.quietManual) }
        var quietFrom by remember { mutableStateOf(quietSettings.quietFrom) }
        var quietTo by remember { mutableStateOf(quietSettings.quietTo) }
        SwitchRow(
            tr("Тихие часы по привычкам"),
            tr("Приложение запоминает, в какие часы недели вы пользуетесь телефоном (только на телефоне, файл входит в резервную копию). Через неделю в «тихие» часы тяжёлое без зарядки не запускается, а перед обычным началом активности туннель проверяется заранее."),
            quietAuto,
        ) { quietAuto = it; quietSettings.quietAuto = it }
        SwitchRow(
            tr("Свои тихие часы"),
            tr("Сильнее автоматических. Тяжёлые фоновые проверки без зарядки в это время не запускаются."),
            quietManual,
        ) { quietManual = it; quietSettings.quietManual = it }
        if (quietManual) {
            Row(Modifier.padding(vertical = 4.dp)) {
                OutlinedButton(onClick = { quietFrom = (quietFrom + 23) % 24; quietSettings.quietFrom = quietFrom }) { Text("−") }
                Text(tr("  с %02d:00  ").format(quietFrom))
                OutlinedButton(onClick = { quietFrom = (quietFrom + 1) % 24; quietSettings.quietFrom = quietFrom }) { Text("+") }
                Text("   ")
                OutlinedButton(onClick = { quietTo = (quietTo + 23) % 24; quietSettings.quietTo = quietTo }) { Text("−") }
                Text(tr("  до %02d:00  ").format(quietTo))
                OutlinedButton(onClick = { quietTo = (quietTo + 1) % 24; quietSettings.quietTo = quietTo }) { Text("+") }
            }
        }
        SwitchRow(
            tr("Подключать VPN само"),
            tr("При включении телефона и при открытии приложения (последний или лучший конфиг). Нужно один раз дать системное разрешение на VPN."),
            autoConnect,
        ) { vm.setAutoConnect(it) }
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("IPv6", style = MaterialTheme.typography.titleMedium)
        Hint(
            tr("Если IPv6 не заводить в туннель, сайты с IPv6 открываются напрямую, мимо VPN (утечка). Блокировка заставляет приложения сразу переходить на IPv4 через VPN. Применяется при следующем подключении.")
        )
        Column(Modifier.padding(top = 4.dp)) {
            Ipv6Mode.entries.forEach { m ->
                FilterChip(selected = ipv6 == m, onClick = { vm.setIpv6(m) }, label = { Text(tr(m.title)) })
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        SwitchRow(
            tr("Не пускать интернет без VPN"),
            tr("Если VPN не удалось поднять или он оборвался, туннель остаётся включённым и трафик блокируется, пока приложение ищет рабочий сервер. По умолчанию выключено: тогда при сбое интернет идёт напрямую."),
            kill,
        ) { vm.setKillSwitch(it) }
        Hint(
            tr("Полная защита (даже когда приложение закрыто или убито системой) включается только в настройках Android: VPN → Phoenix → «Постоянный VPN» и «Блокировать подключения без VPN».")
        )
        OutlinedButton(onClick = {
            try {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(context, tr("Не удалось открыть настройки VPN"), Toast.LENGTH_SHORT).show()
            }
        }) { Text(tr("Настройки VPN в Android")) }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(tr("Плитка в шторке"), style = MaterialTheme.typography.titleMedium)
        Hint(tr("Кнопка «Phoenix VPN» рядом с Wi-Fi и Bluetooth: включает и выключает VPN, долгое нажатие открывает приложение. Первый раз нужно открыть приложение, чтобы дать разрешение на VPN."))
        Spacer(Modifier.height(6.dp))
        if (Build.VERSION.SDK_INT >= 33) {
            Button(onClick = {
                val sbm = context.getSystemService(StatusBarManager::class.java)
                sbm.requestAddTileService(
                    ComponentName(context, PhoenixTileService::class.java),
                    "Phoenix VPN",
                    Icon.createWithResource(context, R.drawable.ic_tile),
                    ContextCompat.getMainExecutor(context),
                ) { }
            }) { Text(tr("Добавить плитку в шторку")) }
        } else {
            Hint(tr("Потяните шторку вниз → значок карандаша (изменить) → перетащите «Phoenix VPN» в активные плитки."))
        }
    }
}

// ---------------------------------------------------------------- Цели (приложения и сайты)

@Composable
fun TargetsPage(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val targets by vm.targets.collectAsStateWithLifecycle()
    val global by vm.globalPolicy.collectAsStateWithLifecycle()
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
        if (global == Policy.AUTO) {
            item {
                Hint(
                    tr("«как везде» у приложения значит: взять выбор, сделанный выше, для всех. Остальные кнопки (авто, А, Б) задают своё для этого приложения.\n\nСайты — всегда по варианту А. Приложение — по А, если ядро умеет узнавать приложение по соединению (тогда остальной трафик не затрагивается); если не умеет — по Б, когда выдан доступ к данным об использовании.")
                )
            }
        }
        if (global == Policy.AUTO || global == Policy.SWITCH || targets.any { it.policy == Policy.SWITCH || it.policy == Policy.AUTO }) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (usage) tr("Доступ к данным об использовании: есть") else tr("Для варианта Б нужен доступ к данным об использовании"),
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
        item { HorizontalDivider() }
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
                        TextButton(onClick = { vm.removeTarget(t.id) }) { Text(tr("Убрать")) }
                    }
                    Hint(tr("проверка: ") + t.checkUrl.removePrefix("https://").removePrefix("http://") +
                        if (t.kind == TargetKind.APP && t.checkUrl == KnownApps.DEFAULT_URL) tr(" (общая: только работа сервера)") else "")
                    if (t.kind == TargetKind.APP) {
                        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = t.policy == Policy.DEFAULT, onClick = { vm.setTargetPolicy(t, Policy.DEFAULT) }, label = { Text(tr("как везде")) })
                            FilterChip(selected = t.policy == Policy.AUTO, onClick = { vm.setTargetPolicy(t, Policy.AUTO) }, label = { Text(tr("авто")) })
                            FilterChip(selected = t.policy == Policy.PROXY, onClick = { vm.setTargetPolicy(t, Policy.PROXY) }, label = { Text(tr("А")) })
                            FilterChip(selected = t.policy == Policy.SWITCH, onClick = { vm.setTargetPolicy(t, Policy.SWITCH) }, label = { Text(tr("Б")) })
                        }
                    } else {
                        Hint(tr("Для сайта работает только вариант А."))
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

/** Раздел «Диагностика»: сводка «что сейчас», журнал с фильтрами и поиском, отчёт последнего сбора. */
@Composable
fun DiagPage(vm: MainViewModel) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tr("Сводка"), tr("Журнал"), tr("Отчёт сбора")).forEachIndexed { i, t ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> SummaryPage(vm)
                1 -> LogPage(vm)
                else -> ReportTab(vm)
            }
        }
    }
}

@Composable
private fun SummaryPage(vm: MainViewModel) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var day by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            summary = vm.diagSummary()
            day = vm.logDay()
        }
    }
    val counts = Diagnostics.counts(day)
    val problems = day.filter { Diagnostics.isProblem(it) }.takeLast(8)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Toast.makeText(context, if (ok) tr("Сохранено: Загрузки/Phoenix") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
            }) { Text(tr("В файл")) }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.forEach { (k, v) ->
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

@Composable
fun LogPage(vm: MainViewModel) {
    val context = LocalContext.current
    var group by remember { mutableStateOf(tr("Всё")) }
    var query by remember { mutableStateOf("") }
    var problemsOnly by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val ioScope = rememberCoroutineScope()

    LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { vm.logLines("", 40_000) } }
    val lines = remember(all, group, query, problemsOnly) { Diagnostics.filter(all, group, query, problemsOnly) }
    val shown = remember(lines) { lines.takeLast(800) }
    val text = remember(shown) { shown.joinToString("\n") { Diagnostics.pretty(it) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Diagnostics.GROUPS.forEach { (title, _) ->
                FilterChip(selected = group == title, onClick = { group = title }, label = { Text(title) })
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
            Text(tr("  Только проблемы"), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(onClick = { tick++ }) { Text(tr("Обновить")) }
            OutlinedButton(onClick = {
                // redact() на 800 строках — регулярные выражения: в главном потоке это давало ANR (журнал 05.11, стек Diagnostics.redact)
                ioScope.launch {
                    val clean = withContext(Dispatchers.IO) { Diagnostics.redact(text) }
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("phoenix-log", clean))
                    Toast.makeText(context, tr("Журнал скопирован"), Toast.LENGTH_SHORT).show()
                }
            }, enabled = shown.isNotEmpty()) { Text(tr("Копировать")) }
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
                    Toast.makeText(context, if (ok) tr("Сохранено: Загрузки/Phoenix") else tr("Не удалось сохранить журнал (за выбранный период записей нет?)"), Toast.LENGTH_SHORT).show()
                }
            }
            OutlinedButton(onClick = { saveLog(true) }, enabled = lines.isNotEmpty() && !saving) { Text(if (saving) tr("Сохраняю…") else tr("В файл: 24 часа")) }
            OutlinedButton(onClick = { saveLog(false) }, enabled = lines.isNotEmpty() && !saving) { Text(tr("В файл: весь")) }
            TextButton(onClick = { vm.clearLogs(); tick++ }) { Text(tr("Очистить")) }
        }
        Text(tr("Записей: {} из {} (на экране последние {}; «В файл» сохраняет по фильтру: за 24 часа или весь журнал). Журнал копится до 5 МБ на режим.", lines.size, all.size, shown.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lines.isEmpty()) {
            Hint(tr("Записей по этому фильтру нет."))
        } else {
            // самые свежие записи внизу; список прокручивается
            val scroll = rememberScrollState()
            LaunchedEffect(lines) { scroll.scrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Text(text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}


// ---------------------------------------------------------------- Экспорт и импорт

@Composable
fun BackupPage(vm: MainViewModel) {
    val status by vm.backupStatus.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Резервная копия"), style = MaterialTheme.typography.titleMedium)
        Hint(
            tr("В файл сохраняются настройки и, для каждого режима (белые и чёрные списки), цели с их адресами проверки и политикой, приложения и сайты в обход VPN, свои источники, чёрный список источников, выбранные сервисы и накопленный опыт конфигов (где они работали). Скачанные конфиги и журнал не входят — они обновляются ночным сбором. Серверы целей тоже не сохраняются, потому что содержат ключи доступа: после импорта они подберутся заново.")
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { export.launch("phoenix-backup.txt") }) { Text(tr("Экспорт в файл")) }
            OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }) { Text(tr("Импорт из файла")) }
        }
        Spacer(Modifier.height(8.dp))
        Hint(
            tr("Импорт ничего не стирает: списки объединяются (чёрный список источников только пополняется), цели добавляются к имеющимся, одиночные настройки берутся из файла. Файл текстовый, его можно открыть и посмотреть. В нём могут быть ссылки на ваши источники — не отправляйте его посторонним.")
        )
        if (status.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


// ---------------------------------------------------------------- Фоновая работа (прошивка)

@Composable
fun FirmwarePage() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var fw by remember { mutableStateOf<FirmwareInfo?>(null) }
    var status by remember { mutableStateOf(Background.status(context)) }
    var dataSaver by remember { mutableStateOf(Background.dataSaverText(context)) }
    LaunchedEffect(Unit) { fw = withContext(Dispatchers.IO) { Background.firmware() } }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = Background.status(context)
                dataSaver = Background.dataSaverText(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Прошивка"), style = MaterialTheme.typography.titleMedium)
        Text(fw?.text() ?: tr("определяю…"), style = MaterialTheme.typography.bodyLarge)
        Hint(tr("Оболочки телефонов (EMUI, MIUI, ColorOS и другие) сами выгружают фоновые приложения. Здесь собрано, что нужно разрешить именно на вашем телефоне."))
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(tr("Что уже разрешено"), style = MaterialTheme.typography.titleMedium)
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        Text(tr("{} Ограничение батареи ", mark(status.ignoringBatteryOptimization)) + if (status.ignoringBatteryOptimization) tr("снято") else tr("действует"), style = MaterialTheme.typography.bodyMedium)
        Text(tr("{} Точные будильники ", mark(status.exactAlarmAllowed)) + if (status.exactAlarmAllowed) tr("разрешены") else tr("не разрешены"), style = MaterialTheme.typography.bodyMedium)
        Text(tr("Экономия трафика: {}", dataSaver), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { Background.requestIgnoreBatteryOptimization(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Снять ограничение батареи")) }
        OutlinedButton(onClick = { Background.requestExactAlarm(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Разрешить точные будильники")) }
        OutlinedButton(onClick = { Background.requestUnrestrictedData(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Данные в фоне без ограничений")) }
        OutlinedButton(onClick = { Background.openNotificationSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Уведомления Phoenix")) }
        OutlinedButton(onClick = { Background.openAutostart(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Автозапуск (MIUI / HyperOS)")) }
        OutlinedButton(onClick = { Background.openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Настройки приложения")) }

        val guide = fw?.let { FirmwareGuides.forKind(it.kind) }
        if (guide != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(guide.title, style = MaterialTheme.typography.titleMedium)
            guide.steps.forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            guide.actions.forEach { a ->
                OutlinedButton(onClick = { Background.openFirst(context, a.screens) }, modifier = Modifier.fillMaxWidth()) { Text(a.label) }
            }
            Hint(tr("Названия пунктов зависят от версии прошивки. Если кнопка не нашла нужный экран, откроются настройки приложения — дальше по шагам выше."))
        }
    }
}
