package com.phoenix.app

import android.content.Context
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Хранение пожеланий к выбору сервера (см. [PoolPolicy]) и их применение. */
object PoolPolicyStore {
    fun load(context: Context) {
        val s = AppSettings(context)
        PoolPolicy.configure(PoolPolicy.decode(s.poolPrefer), PoolPolicy.decode(s.poolAvoid), PoolPolicy.decode(s.poolHidden))
    }

    private fun save(context: Context, prefer: Set<String>, avoid: Set<String>, hidden: Set<String>) {
        val s = AppSettings(context)
        s.poolPrefer = PoolPolicy.encode(prefer)
        s.poolAvoid = PoolPolicy.encode(avoid)
        s.poolHidden = PoolPolicy.encode(hidden)
        PoolPolicy.configure(prefer, avoid, hidden)
    }

    fun togglePrefer(context: Context, cc: String) {
        val on = cc in PoolPolicy.prefer
        save(context, if (on) PoolPolicy.prefer - cc else PoolPolicy.prefer + cc, PoolPolicy.avoid - cc, PoolPolicy.hidden)
    }

    fun toggleAvoid(context: Context, cc: String) {
        val on = cc in PoolPolicy.avoid
        save(context, PoolPolicy.prefer - cc, if (on) PoolPolicy.avoid - cc else PoolPolicy.avoid + cc, PoolPolicy.hidden)
    }

    fun hide(context: Context, identity: String) {
        save(context, PoolPolicy.prefer, PoolPolicy.avoid, PoolPolicy.hidden + identity)
        try { Storage(context).log("Серверы: скрыт один сервер (всего скрытых ${PoolPolicy.hidden.size})") } catch (_: Throwable) { }
    }

    fun unhide(context: Context, identity: String) {
        save(context, PoolPolicy.prefer, PoolPolicy.avoid, PoolPolicy.hidden - identity)
    }
}

/** Флаг по коду страны (региональные индикаторы). */
fun flagEmoji(cc: String): String {
    if (cc.length != 2) return ""
    val a = cc.uppercase()
    if (a.any { it !in 'A'..'Z' }) return ""
    return String(Character.toChars(0x1F1E6 + (a[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (a[1] - 'A')))
}

fun countryTitle(cc: String): String {
    val name = try { java.util.Locale.Builder().setRegion(cc).build().getDisplayCountry(java.util.Locale.getDefault()) } catch (_: Throwable) { "" }
    return (flagEmoji(cc) + " " + name.ifBlank { cc }).trim()
}

/** Страница «Страны и серверы»: кого предпочитать, к кому не подключаться, скрытые серверы. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountriesPage(vm: MainViewModel) {
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    var version by remember { mutableStateOf(0) }
    val pool = remember(rows) {
        rows.mapNotNull { it.cc?.uppercase()?.takeIf { c -> c.length == 2 } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    }
    // выбранные страны показываем и тогда, когда в пуле их сейчас нет
    version.let { }
    val all = (pool.map { it.key } + PoolPolicy.prefer + PoolPolicy.avoid).distinct()
    val count = pool.associate { it.key to it.value }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (PoolPolicy.relaxed) {
            Text(tr("Ограничение временно снято: подходящих живых серверов нет"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (all.isEmpty()) {
            Text(tr("Стран пока нет: сначала соберите серверы"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(tr("Предпочитать серверы из"), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEach { cc ->
                    FilterChip(selected = cc in PoolPolicy.prefer, onClick = { PoolPolicyStore.togglePrefer(context, cc); version++ },
                        label = { Text(countryTitle(cc) + (count[cc]?.let { " · $it" } ?: "")) })
                }
            }
            Text(tr("Такие серверы идут раньше остальных живых; медленные и мёртвые это не спасает."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(tr("Никогда не подключаться через"), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEach { cc ->
                    FilterChip(selected = cc in PoolPolicy.avoid, onClick = { PoolPolicyStore.toggleAvoid(context, cc); version++ },
                        label = { Text(countryTitle(cc) + (count[cc]?.let { " · $it" } ?: "")) })
                }
            }
            Text(tr("В режиме белых списков российские выходы часто единственные рабочие: исключайте их с осторожностью."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        val hidden = PoolPolicy.hidden.toList()
        Text(if (hidden.isEmpty()) tr("Скрытых серверов нет") else tr("Скрытые серверы ({})", hidden.size), style = MaterialTheme.typography.titleMedium)
        if (hidden.isEmpty()) {
            Text(tr("Скрыть сервер можно в «Серверы»: откройте строку и нажмите «Не использовать»."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val byIdentity = remember(rows) { rows.associateBy { ConfigIdentity.key(it.raw) } }
        hidden.forEach { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(DisplayName.plain(byIdentity[id]?.displayName ?: id).take(60), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { PoolPolicyStore.unhide(context, id); version++ }) { Text(tr("Вернуть")) }
            }
        }
    }
}
