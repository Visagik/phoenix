package com.phoenix.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Просьба показать список серверов: из кнопки в приложении, уведомления, плитки и виджета (все открывают приложение с этим флагом). */
object SwitchRequest {
    const val EXTRA_SHOW = "show_switch"
    val show = MutableStateFlow(false)
}

/** Данные списка: рабочие серверы текущего режима в порядке рейтинга. */
object ServerChoiceLoader {
    fun load(context: Context): List<ServerOption> {
        val storage = Storage(context)
        val rows = ConfigRepository.load(storage)
        if (rows.isEmpty()) return emptyList()
        val checks = CheckStore(storage.checksFile).load()
        val ranked = Ranking.rank(
            Ranking.candidates(rows, checks, history = storage.historyView(context), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())
        )
        return ServerChoice.options(ranked, rows, checks, storage.checkSelection().selected(), VpnState.status.value.link,
            odds = CheckStore(storage.checksFile).odds(),
            exitCc = ExitStore(storage.exitFile).load().let { m -> { key: String -> m[key]?.cc } })
    }
}

/** Окно «Сменить сервер»: список всех рабочих серверов с местом, сервисами и откликом; выбор переключает VPN. */
@Composable
fun ServerSwitchHost() {
    val context = LocalContext.current
    val show by SwitchRequest.show.collectAsStateWithLifecycle()
    if (!show) return
    val status by VpnState.status.collectAsStateWithLifecycle()
    val options by produceState<List<ServerOption>?>(null, status.link) {
        value = withContext(Dispatchers.IO) { ServerChoiceLoader.load(context) }
    }
    val close = { SwitchRequest.show.value = false }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Сменить сервер") },
        text = {
            val list = options
            when {
                status.phase != VpnPhase.CONNECTED -> Text("VPN не подключён — менять нечего.")
                list == null -> Text("Загружаю список…")
                list.isEmpty() -> Text("Серверов в списке нет. Сначала соберите и проверьте конфиги.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                PhoenixVpnService.switchServer(context)
                                close()
                            }.padding(vertical = 10.dp)
                        ) {
                            Text("Автоматически — лучший другой", fontWeight = FontWeight.Bold)
                            Text("приложение само найдёт и проверит рабочий сервер", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                    items(list, key = { it.link }) { o ->
                        var showWhy by remember(o.link) { mutableStateOf(false) }
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = !o.current) {
                                PhoenixVpnService.switchTo(context, o.link, o.title)
                                close()
                            }.padding(vertical = 10.dp)
                        ) {
                            Text(o.title + if (o.current) "  — сейчас" else "", fontWeight = if (o.current) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
                            val line = listOf(o.place + if (o.exitChecked) " ✓" else "", o.speed).filter { it.isNotBlank() }.joinToString(" · ")
                            Text(line + if (!o.checked) " · не проверен" else "", style = MaterialTheme.typography.bodySmall)
                            if (o.why.isNotBlank()) {
                                Text(
                                    if (showWhy) "скрыть причины" else "почему этот сервер?",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showWhy = !showWhy }.padding(vertical = 2.dp),
                                )
                                if (showWhy) Text(o.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (o.apps.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    o.apps.forEach { a ->
                                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(a.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(a.mark + (a.percent?.let { " $it%" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { close() }) { Text("Закрыть") } },
    )
}
