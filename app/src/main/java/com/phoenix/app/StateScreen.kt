package com.phoenix.app

import android.app.NotificationManager
import android.content.Context
import android.net.VpnService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Одна проверка прав: [ok] — есть/нет, [fix] — куда перейти, чтобы исправить (null — нечего открывать или не проверяется). */
data class PermCheck(val title: String, val ok: Boolean, val detail: String, val optional: Boolean = false, val fix: (() -> Unit)? = null)

object StateInfo {
    fun permissions(context: Context): List<PermCheck> {
        val bg = Background.status(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val vpnOk = try { VpnService.prepare(context) == null } catch (_: Throwable) { false }
        return listOf(
            PermCheck(tr("Разрешение на VPN"), vpnOk, if (vpnOk) tr("выдано") else tr("будет запрошено при первом подключении")),
            PermCheck(tr("Уведомления"), nm?.areNotificationsEnabled() == true, if (nm?.areNotificationsEnabled() == true) tr("включены") else tr("выключены — служба VPN может не показываться"),
                fix = { Background.openNotificationSettings(context) }),
            PermCheck(tr("Ограничение батареи снято"), bg.ignoringBatteryOptimization, if (bg.ignoringBatteryOptimization) tr("да") else tr("нет — система может выгружать приложение"),
                fix = { Background.requestIgnoreBatteryOptimization(context) }),
            PermCheck(tr("Точные будильники"), bg.exactAlarmAllowed, if (bg.exactAlarmAllowed) tr("разрешены") else tr("нет — сбор по расписанию может сдвигаться"),
                fix = { Background.requestExactAlarm(context) }),
            clockCheck(context),
            run {
                val st = AppSettings(context)
                val v = Background.vendor()
                PermCheck(tr("Автозапуск и фон ({})", v.title), st.autostartConfirmed,
                    (if (st.autostartConfirmed) tr("отмечено вами (система не даёт проверить); нажмите, чтобы открыть снова. ") else tr("система не даёт проверить сама: нажмите, включите и вернитесь — отметка сохранится. ")) + OemGuide.hint(v),
                    optional = true, fix = { Background.openAutostart(context); st.autostartConfirmed = true })
            },
            PermCheck(tr("Доступ к статистике использования"), ForegroundWatcher.hasAccess(context), tr("нужен только для варианта Б (переключение VPN при открытии приложения)"),
                optional = true, fix = { ForegroundWatcher.openAccessSettings(context) }),
        )
    }

    /** Адрес мимо VPN и через VPN (внутренний SOCKS ядра) + страна выхода. Работает только при включённом VPN. */
    fun leakTest(): String {
        fun direct(): String? = try {
            val c = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 5_000; c.readTimeout = 5_000
            try { LeakVerdict.parseIp(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        } catch (_: Throwable) { null }
        val port = LocalProxy.port
        val auth = LocalProxy.auth
        val viaVpn = if (port > 0 && auth != null) try {
            LocalSocks.get(port, auth, "https://api.ipify.org").use { r -> if (r.code == 200) LeakVerdict.parseIp(r.head(128)) else null }
        } catch (_: Throwable) { null } else null
        val country = if (port > 0 && auth != null) try {
            LocalSocks.get(port, auth, "https://api.country.is/").use { r ->
                if (r.code == 200) Regex("\"country\"\\s*:\\s*\"([A-Za-z]{2})\"").find(r.head(256))?.groupValues?.get(1)?.uppercase() else null
            }
        } catch (_: Throwable) { null } else null
        val d = direct()
        return LeakVerdict.text(LeakVerdict.of(d, viaVpn), d, viaVpn, country)
    }

    /** Часы телефона против заголовка Date (простой http-запрос без TLS). Нет сети — «не проверено». */
    private fun clockCheck(context: Context): PermCheck {
        val skew = try {
            val c = java.net.URL("http://cp.cloudflare.com/generate_204").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4_000; c.readTimeout = 4_000; c.instanceFollowRedirects = false
            try { c.responseCode; ClockSkew.skewSeconds(c.getHeaderField("Date"), System.currentTimeMillis()) } finally { c.disconnect() }
        } catch (_: Throwable) { null }
        return when {
            skew == null -> PermCheck(tr("Время телефона"), false, tr("не проверено (нет ответа сети)"), optional = true)
            ClockSkew.bad(skew) -> PermCheck(tr("Время телефона"), false, tr("{} — TLS и Reality могут не работать; включите автоматическое время", ClockSkew.text(skew)),
                fix = { try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {} })
            else -> PermCheck(tr("Время телефона"), true, tr("верное (расхождение {} с)", Math.abs(skew)))
        }
    }

    /** Сколько места занимают журнал и данные режима, человекочитаемо. */
    fun sizes(context: Context): List<Pair<String, String>> {
        fun fmt(b: Long) = if (b >= 1024 * 1024) tr("%.1f МБ").format(b / 1048576.0) else tr("{} КБ", b / 1024)
        return Mode.entries.map { m ->
            val st = Storage(context, m)
            tr("Журнал «{}»", tr(m.title)) to fmt(if (st.logFile.exists()) st.logFile.length() else 0L) + tr(" из 5 МБ")
        }
    }
}

/** Единый экран «Состояние»: права галочками, что сейчас происходит, размер журнала. */
@Composable
fun StatePage(vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    var perms by remember { mutableStateOf<List<PermCheck>>(emptyList()) }
    var summary by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sizes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var eventsTick by remember { mutableStateOf(0) }
    var events by remember { mutableStateOf<List<EventTimeline.Item>?>(null) }
    LaunchedEffect(eventsTick) {
        if (eventsTick == 0) return@LaunchedEffect
        events = withContext(Dispatchers.IO) { vm.recentEvents() }
    }
    var energy by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tick) { energy = withContext(Dispatchers.IO) { vm.energyLines() } }
    var stats by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tick) { stats = withContext(Dispatchers.IO) { vm.statLines() } }
    var undoTick by remember { mutableStateOf(0) }
    var undoList by remember { mutableStateOf<List<UndoStore.Entry>>(emptyList()) }
    var undoNote by remember { mutableStateOf("") }
    LaunchedEffect(undoTick, tick) { undoList = withContext(Dispatchers.IO) { vm.undoEntries() } }
    var leakTick by remember { mutableStateOf(0) }
    var leakText by remember { mutableStateOf("") }
    LaunchedEffect(leakTick) {
        if (leakTick == 0) return@LaunchedEffect
        leakText = tr("проверяю…")
        leakText = withContext(Dispatchers.IO) { StateInfo.leakTest() }
    }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            perms = StateInfo.permissions(context)
            summary = vm.diagSummary()
            sizes = StateInfo.sizes(context)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Права и фоновая работа"), style = MaterialTheme.typography.titleMedium)
        perms.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (p.ok) "✓" else if (p.optional) "–" else "✗", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium)
                    Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!p.ok && p.fix != null) TextButton(onClick = { p.fix.invoke() }) { Text(tr("Открыть")) }
            }
        }
        Text(
            tr("Автозапуск и «Не выгружать из памяти» на MIUI/HyperOS, Funtouch, EMUI программно не проверить — их смотрите на странице «Фоновая работа (прошивка)»."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(tr("Сейчас"), style = MaterialTheme.typography.titleMedium)
        summary.forEach { (k, v) ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(tr("Статистика"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Сколько VPN работал, как часто менялся сервер и на каких серверах держался дольше всего. Хранится только на телефоне, 30 дней."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        stats.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(tr("Что произошло?"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Главные события за последние 15 минут из журнала: падения сервера, смены сети, поиск, переключения."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { eventsTick++ }) { Text(tr("Показать")) }
        events?.let { list ->
            if (list.isEmpty()) Text(tr("За последние 15 минут ничего важного не происходило."), style = MaterialTheme.typography.bodyMedium)
            list.forEach { Text("${it.time}  ${it.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        if (energy.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(tr("Что приложение делает само"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Оценка расхода на проверки (трафик приблизительный). Если цифры большие, включите «Фокус» или тихие часы."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            energy.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        if (undoList.isNotEmpty() || undoNote.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(tr("Можно вернуть"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Приложение само меняет чёрный список источников и их приоритеты. Любое такое изменение можно отменить в течение суток."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            undoList.forEach { e ->
                val ago = ((System.currentTimeMillis() - e.at) / 60_000L).coerceAtLeast(0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.label, style = MaterialTheme.typography.bodyMedium)
                        Text(if (ago < 60) tr("{} мин назад", ago) else tr("{} ч назад", ago / 60), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        undoNote = if (vm.undo(e.id)) tr("Вернул: {}", e.label) else tr("Не удалось вернуть (запись устарела)")
                        undoTick++
                    }) { Text(tr("Вернуть")) }
                }
            }
            if (undoNote.isNotEmpty()) Text(undoNote, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(tr("Проверка утечек"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("Сравнивает адрес «мимо VPN» и «через VPN». Приложение само вне туннеля, поэтому DNS-утечки других приложений отсюда не видны."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { leakTick++ }) { Text(tr("Проверить")) }
        if (leakText.isNotEmpty()) Text(leakText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(tr("Данные"), style = MaterialTheme.typography.titleMedium)
        sizes.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = MaterialTheme.typography.bodyMedium)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
