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
            tr("В файл сохраняются настройки и, для каждого режима (белые и чёрные списки), цели с их адресами проверки и политикой, приложения и сайты в обход VPN, свои источники, чёрный список источников, выбранные сервисы, накопленный опыт конфигов (где они работали) и журнал (при импорте он сливается с текущим). Скачанные конфиги не входят — они обновляются ночным сбором. Серверы целей тоже не сохраняются, потому что содержат ключи доступа: после импорта они подберутся заново.")
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
        // автокопия: раз в неделю во внутреннюю память, последние три
        val abContext = LocalContext.current
        val abSettings = remember { AppSettings(abContext) }
        var autoBackupOn by remember { mutableStateOf(abSettings.autoBackup) }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            tr("Автокопия раз в неделю"),
            tr("Копия настроек и данных без журнала сохраняется во внутреннюю память приложения после планового сбора, хранятся последние три. При удалении приложения она пропадёт — для этого есть экспорт в файл."),
            autoBackupOn,
        ) { autoBackupOn = it; abSettings.autoBackup = it }
        val lastAuto = abSettings.lastAutoBackupAt
        Hint(
            if (lastAuto > 0) tr("Последняя автокопия: {}", java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastAuto)))
            else tr("Автокопий пока нет: первая появится после ближайшего сбора."),
        )
        OutlinedButton(onClick = { vm.restoreAutoBackup() }) { Text(tr("Восстановить из последней автокопии")) }
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
    var perms by remember { mutableStateOf<List<PermCheck>>(emptyList()) }
    var dataSaver by remember { mutableStateOf(Background.dataSaverText(context)) }
    var tick by remember { mutableStateOf(0) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { fw = withContext(Dispatchers.IO) { Background.firmware() } }
    // права проверяются при открытии и при возврате из системных настроек, а не при каждой отрисовке
    LaunchedEffect(tick) {
        dataSaver = Background.dataSaverText(context)
        perms = withContext(Dispatchers.IO) { StateInfo.permissions(context, withClock = false) }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(tr("Прошивка"), style = MaterialTheme.typography.titleMedium)
        Text(fw?.text() ?: tr("определяю…"), style = MaterialTheme.typography.bodyLarge)
        Hint(tr("Оболочки телефонов (EMUI, MIUI, ColorOS и другие) сами выгружают фоновые приложения. Здесь собрано, что нужно разрешить именно на вашем телефоне."))
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.height(8.dp))
        Text(tr("Что уже разрешено"), style = MaterialTheme.typography.titleMedium)
        // одно место для всех прав: кнопка «Исправить» только у невыданных
        val okColor = PhoenixTheme.ok(appDark())
        perms.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (p.ok) "✓" else if (p.optional) "–" else "✗",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (p.ok) okColor else if (p.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium)
                    Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!p.ok && p.fix != null) TextButton(onClick = { p.fix.invoke() }) { Text(tr("Исправить")) }
            }
        }
        Text(tr("Экономия трафика: {}", dataSaver), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (android.os.Build.VERSION.SDK_INT >= 24) TextButton(onClick = { Background.requestUnrestrictedData(context) }) { Text(tr("Данные в фоне без ограничений")) }
        }
        TextButton(onClick = { Background.openAppSettings(context) }) { Text(tr("Настройки приложения")) }

        val guide = fw?.let { FirmwareGuides.forKind(it.kind) }
        if (guide != null) {
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().clickable { showGuide = !showGuide }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(guide.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (showGuide) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (showGuide) {
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
}
