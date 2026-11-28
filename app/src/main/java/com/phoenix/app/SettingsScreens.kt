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
internal fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        // длинное пояснение сворачивается до двух строк; нажатие на текст раскрывает его целиком
        var expanded by rememberSaveable(title) { mutableStateOf(false) }
        val long = hint.length > 90
        Column(Modifier.weight(1f).clickable(enabled = long) { expanded = !expanded }) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** Заголовок группы настроек: небольшой, цветом основного действия. */
@Composable
fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

// ---------------------------------------------------------------- Режим блокировок

@Composable
fun ModePage(vm: MainViewModel) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val auto by vm.autoMode.collectAsStateWithLifecycle()
    val detect by vm.detectText.collectAsStateWithLifecycle()
    // результат определения обновляет служба в фоне: пока экран открыт, перечитываем его (чтение настроек, почти бесплатно)
    LaunchedEffect(Unit) { while (true) { vm.refreshDetect(); delay(5_000) } }
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
            tr("Приложение проверяет, какие сайты открываются напрямую, и переключает режим. После смены сети хватает одного определения, иначе нужно два подряд. Проверка идёт при смене сети, при обрыве связи и при запуске."),
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
    val dnsId by vm.dnsProvider.collectAsStateWithLifecycle()
    val dnsTrialAt by vm.dnsTrialAt.collectAsStateWithLifecycle()
    var resetTick by remember { mutableStateOf(0) }
    var askReset by remember { mutableStateOf(false) }

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
            if (XrayCore.available) tr("Xray: версия {}", remember { XrayCore.version() }) else XrayCore.problem + if (XrayCore.present) tr(" Сейчас: {}.", XrayCore.version()) else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (XrayCore.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        var net by remember { mutableStateOf(NetInfo.kind(context)) }
        LaunchedEffect(Unit) { net = NetInfo.kind(context) }
        Text(tr("Сеть сейчас: {}", tr(net.title)), style = MaterialTheme.typography.bodyMedium)
        Hint(
            if (NetInfo.isUnmetered(context)) tr("Замеры скорости идут сами.")
            else if (metered) tr("Замеры идут и по мобильной сети (включено ниже).")
            else tr("Замеры скорости сами не идут (мобильная сеть); кнопка «Скорость» на карточке работает всегда.")
        )
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.height(8.dp))
        Text(tr("Чьи настройки править"), style = MaterialTheme.typography.titleMedium)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = scope == AppSettings.Scope.COMMON, onClick = { vm.setScope(AppSettings.Scope.COMMON) }, label = { Text(tr("Общие")) })
            if (Mode.WHITELIST !in LiteProfile.skipModes()) {
                FilterChip(selected = scope == AppSettings.Scope.WHITELIST, onClick = { vm.setScope(AppSettings.Scope.WHITELIST) }, label = { Text(tr("Белые")) })
            }
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
        Spacer(Modifier.height(4.dp))
        if (!editable) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Сейчас для этого режима действуют общие настройки. Включите «Свои настройки», чтобы менять их отдельно."), style = MaterialTheme.typography.bodyMedium)
        }
        if (editable) {
        GroupLabel(tr("Для выбранной области"))
        GroupLabel(tr("Смена сервера"))
        SwitchRow(
            tr("Переподключаться само"),
            tr("Если туннель перестал работать (неудачных проверок подряд: {} на Wi-Fi, {} на мобильной сети), приложение перебирает запасные конфиги лучшего рейтинга.", HealthGate.failuresNeeded(false), HealthGate.failuresNeeded(true)),
            reconnect,
        ) { vm.setAutoReconnect(it) }
        SwitchRow(
            tr("Менять сервер при низкой скорости"),
            tr("Бывает, что интернет «есть», но фото и страницы почти не грузятся. Пока вы пользуетесь телефоном, раз в {} секунд смотрится, сколько данных идёт через сервер; если мало, делается один маленький замер (до 400 КБ), и после двух медленных подряд пробуются другие серверы. Если телефон не используется — ничего не качается. На мобильной сети работает, только если включено «Измерять скорость и по мобильной сети» и не исчерпан суточный лимит.", PhoenixVpnService.SPEED_PERIOD_MS / 1000),
            slowOn,
        ) { vm.setSlowSwitch(it) }
        if (!LiteProfile.ON && slowOn && !metered && !NetInfo.isUnmetered(context)) {
            Text(tr("Сейчас мобильная сеть: без замеров по ней эта функция не работает"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
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
        if (!LiteProfile.ON) {
            SwitchRow(
                tr("Измерять скорость и по мобильной сети"),
                tr("Замер скачивает до 8 МБ. По умолчанию — только по Wi-Fi."),
                metered,
            ) { vm.setSpeedOnMetered(it) }
        }
        SwitchRow(
            tr("Закрыть QUIC (UDP 443)"),
            tr("Многие серверы не пропускают UDP: приложения ждут ответа и не открываются, хотя Telegram работает. При закрытом QUIC они сразу переходят на обычный TCP. Рекомендуется оставить включённым. Применяется при следующем подключении."),
            quic,
        ) { vm.setBlockQuic(it) }
        SwitchRow(
            tr("Сам перепроверять все конфиги"),
            tr("Сам запускает полную перепроверку после ночного сбора и когда запасной сервер не нашёлся (не чаще раза в 30 минут). После ручного «Собрать» перепроверка идёт всегда."),
            autoRecheck,
        ) { vm.setAutoRecheck(it) }
        GroupLabel(tr("Запуск и защита"))
        SwitchRow(
            tr("Подключать VPN само"),
            tr("При включении телефона и при открытии приложения (последний или лучший конфиг). Если VPN был включён, после перезагрузки он поднимется и при выключенной настройке. Нужно один раз дать системное разрешение на VPN."),
            autoConnect,
        ) { vm.setAutoConnect(it) }
        Spacer(Modifier.height(4.dp))
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
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            tr("Не пускать интернет без VPN"),
            tr("Если подключиться не удалось: при включённой настройке туннель остаётся, и интернета нет, пока приложение ищет рабочий сервер; при выключенной телефон выходит в интернет напрямую. Если связь пропала посреди работы, туннель держится в любом случае, пока ищется другой сервер."),
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
        GroupLabel(tr("Для всех режимов"))
        Hint(tr("Действуют в любом режиме и не зависят от выбора «Общие / Белые / Чёрные»."))
        // как долго держится сервер, выбранный вручную (кнопкой «Сменить сервер» или в списке): автоперебор его не трогает
        val pinSettings = remember { AppSettings(context) }
        var pinHours by remember(resetTick) { mutableStateOf(pinSettings.pinHours) }
        GroupLabel(tr("Закреплённый сервер"))
        Hint(tr("Сервер, выбранный вручную, сам не меняется. Через это время закрепление снимается, и приложение снова выбирает сервер само."))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinPolicy.OPTIONS.forEach { h ->
                FilterChip(
                    selected = pinHours == h,
                    onClick = { pinHours = h; pinSettings.pinHours = h },
                    label = { Text(tr(PinPolicy.label(h))) },
                )
            }
        }
        // тихие часы: тяжёлое (перепроверка, аудит) без зарядки не запускается
        val quietSettings = remember { AppSettings(context) }
        var focus by remember(resetTick) { mutableStateOf(quietSettings.focusMode) }
        SwitchRow(
            tr("Режим «Фокус»"),
            tr("Пока открыто приложение из ваших целей (игра, банк, звонок), приложение не запускает тяжёлые проверки и не меняет сервер по скорости. Нужен доступ к данным об использовании."),
            focus,
        ) { focus = it; quietSettings.focusMode = it }
        if (!LiteProfile.ON) {
            var appAware by remember(resetTick) { mutableStateOf(quietSettings.appAwareFailover) }
            SwitchRow(
                tr("Смена сервера с учётом открытого приложения"),
                tr("Когда сервер перестаёт работать и приложение само ищет другой, оно смотрит, какое приложение открыто сейчас (нужен доступ к данным об использовании), и пробует первыми серверы, на которых это приложение уже открывалось. Если у приложения есть своя цель — сначала её серверы."),
                appAware,
            ) { appAware = it; quietSettings.appAwareFailover = it }
        }
        // вернулись из системных настроек доступности — статус обновляется сам, а не только после перезахода на страницу
        val resumed = rememberResumeTick()
        val siteWatching = remember(resumed) {
            CurrentSite.isListed(
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                context.packageName, "SiteWatcherService",
            )
        }
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
        GroupLabel(tr("Тихие часы"))
        var quietAuto by remember { mutableStateOf(quietSettings.quietAuto) }
        var quietManual by remember { mutableStateOf(quietSettings.quietManual) }
        var quietFrom by remember { mutableStateOf(quietSettings.quietFrom) }
        var quietTo by remember { mutableStateOf(quietSettings.quietTo) }
        SwitchRow(
            tr("Тихие часы по привычкам"),
            tr("Приложение запоминает, в какие часы недели вы пользуетесь телефоном (только на телефоне, файл входит в резервную копию) и через неделю проверяет туннель заранее, перед обычным началом активности. Без зарядки тяжёлые проверки и так не запускаются; в тихие часы не делается даже редкое исключение «давно не проверяли»."),
            quietAuto,
        ) { quietAuto = it; quietSettings.quietAuto = it }
        SwitchRow(
            tr("Свои тихие часы"),
            tr("То же, но по заданным вами часам; сильнее автоматических."),
            quietManual,
        ) { quietManual = it; quietSettings.quietManual = it }
        if (quietManual) {
            FlowRow(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { quietFrom = (quietFrom + 23) % 24; quietSettings.quietFrom = quietFrom }) { Text("−") }
                Text(tr("  с %02d:00  ").format(quietFrom))
                OutlinedButton(onClick = { quietFrom = (quietFrom + 1) % 24; quietSettings.quietFrom = quietFrom }) { Text("+") }
                Text("   ")
                OutlinedButton(onClick = { quietTo = (quietTo + 23) % 24; quietSettings.quietTo = quietTo }) { Text("−") }
                Text(tr("  до %02d:00  ").format(quietTo))
                OutlinedButton(onClick = { quietTo = (quietTo + 1) % 24; quietSettings.quietTo = quietTo }) { Text("+") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.height(4.dp))
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
        Spacer(Modifier.height(16.dp))
        Text(tr("DNS в туннеле"), style = MaterialTheme.typography.titleMedium)
        Hint(tr("Какие серверы имён используют приложения внутри VPN. Если сайты не открываются, попробуйте другой. При смене у вас 30 секунд, чтобы проверить сайт и подтвердить; иначе вернётся прежний."))
        DnsProvider.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = dnsId == p.name, onClick = { vm.setDnsProvider(p.name) }, enabled = dnsTrialAt <= 0L)
                Text(tr(p.title))
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { askReset = true }) { Text(tr("Вернуть рекомендуемые")) }
        Hint(tr("Сбрасывает только настройки этого экрана для выбранной области (Общие / Белые / Чёрные)."))
    }
    if (dnsTrialAt > 0L) {
        var now by remember(dnsTrialAt) { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(dnsTrialAt) {
            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
        }
        AlertDialog(
            onDismissRequest = { },
            title = { Text(tr("Проверка DNS")) },
            text = { Text(tr("Включён: {}. Откройте любой сайт или приложение. Если всё работает, нажмите «Оставить». Через {} с вернётся прежний.", tr(DnsProvider.fromId(dnsId).title), DnsTrial.remainingSec(dnsTrialAt, now))) },
            confirmButton = { TextButton(onClick = { vm.confirmDns() }) { Text(tr("Оставить")) } },
            dismissButton = { TextButton(onClick = { vm.rollbackDns() }) { Text(tr("Вернуть прежний")) } },
        )
    }
    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text(tr("Вернуть рекомендуемые")) },
            text = { Text(tr("К значениям по умолчанию вернутся: {}. Продолжить?", SettingsKeys.resetBaseKeys(editMode?.name).joinToString(", ") { tr(SettingsKeys.LABELS[it] ?: it) })) },
            confirmButton = {
                TextButton(onClick = {
                    askReset = false
                    val n = vm.resetRecommended()
                    resetTick++
                    Toast.makeText(context, if (n > 0) tr("Возвращено настроек: {}", n) else tr("Уже стоят рекомендуемые"), Toast.LENGTH_SHORT).show()
                }) { Text(tr("Вернуть")) }
            },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text(tr("Отмена")) } },
        )
    }
}

// ---------------------------------------------------------------- Цели (приложения и сайты)
