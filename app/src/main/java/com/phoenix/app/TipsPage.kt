package com.phoenix.app

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Switch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** «Ещё → Фишки»: то, о чём в настройках не узнаешь. Только описания и кнопки «добавить»/«открыть», настроек здесь нет. */
@Composable
fun TipsPage(onOpenTargets: () -> Unit, onOpenSources: () -> Unit = {}) {
    val context = LocalContext.current
    @Composable
    fun Tip(title: String, body: String, actions: @Composable () -> Unit = {}) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                actions()
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Tip(tr("Свой сервер для приложений"), tr("Привяжите отдельный сервер к Telegram, ChatGPT и другим: Phoenix проверяет и держит именно его.")) {
            FilledTonalButton(onClick = onOpenTargets) { Text(tr("Открыть «Свой сервер»")) }
        }
        Tip(tr("Кнопка «Не работает»"), tr("На главной: если сервис не открывается, нажмите её, и Phoenix сам найдёт и поставит другой сервер."))
        Tip(tr("Плитки в шторке"), tr("Три плитки: «Phoenix VPN» включает и выключает защиту, «Сменить сервер» ставит другой, «Не работает» запускает умную смену.")) {
            if (Build.VERSION.SDK_INT >= 33) {
                listOf(
                    Triple(PhoenixTileService::class.java, "Phoenix VPN", 0),
                    Triple(SwitchTileService::class.java, "Сменить сервер", 1),
                    Triple(SmartTileService::class.java, "Не работает", 2),
                ).forEach { (cls, label, _) ->
                    FilledTonalButton(onClick = {
                        try {
                            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                ComponentName(context, cls), label, Icon.createWithResource(context, R.drawable.ic_tile), ContextCompat.getMainExecutor(context),
                            ) { }
                        } catch (_: Throwable) { }
                    }) { Text(tr("Добавить: {}", label)) }
                }
            } else {
                Text(tr("Потяните шторку вниз → значок карандаша → перетащите плитки Phoenix в активные."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Tip(tr("Ярлыки на значке"), tr("Долгое нажатие на значок Phoenix: «Не работает», «Сменить сервер», «Отключить» — не открывая приложение."))
        Tip(tr("Виджет"), tr("Виджет на рабочем столе показывает состояние и меняет сервер одним нажатием.")) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) {
                FilledTonalButton(onClick = {
                    try { manager.requestPinAppWidget(ComponentName(context, PhoenixWidgetProvider::class.java), null, null) } catch (_: Throwable) { }
                }) { Text(tr("Добавить виджет")) }
            }
        }
        Tip(tr("Значок в строке состояния"), tr("Вверху экрана, рядом с часами, Phoenix показывает значок: он меняется по ситуации, а в шторке меняется и цвет уведомления.")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(
                    Triple(R.drawable.ic_stat_ok, NotifyTone.GREEN, tr("Работает")),
                    Triple(R.drawable.ic_stat_search, NotifyTone.YELLOW, tr("Ищет сервер")),
                    Triple(R.drawable.ic_stat_error, NotifyTone.RED, tr("Нет связи")),
                ).forEach { (res, tone, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(res), contentDescription = label, tint = Color(tone.argb), modifier = Modifier.size(36.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        var ticker by remember { mutableStateOf(AppSettings(context).factsTicker) }
        Tip(tr("Бегущая строка"), tr("Вверху главного экрана по одному показывается факт о работе Phoenix; длинный едет по кругу, нажатие — следующий факт.")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Показывать на главном экране"), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = ticker, onCheckedChange = { ticker = it; AppSettings(context).factsTicker = it })
            }
        }
        Tip(tr("Свои подписки"), tr("Добавьте свою ссылку на подписку или список серверов: она попадёт в сбор вместе со встроенными источниками.")) {
            FilledTonalButton(onClick = onOpenSources) { Text(tr("Открыть «Источники»")) }
        }
        Tip(tr("«Поделиться» ссылкой"), tr("Откройте ссылку на подписку в любом приложении, нажмите «Поделиться» и выберите Phoenix: ссылка попадёт в ваши источники."))
    }
}
