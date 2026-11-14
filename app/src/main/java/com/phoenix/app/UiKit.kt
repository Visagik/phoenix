package com.phoenix.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Подтверждение необратимого действия (очистка журнала, удаление цели или сервиса): одно окно на весь интерфейс. */
@Composable
fun ConfirmDialog(title: String, text: String, onYes: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onYes(); onDismiss() }) { Text(tr("Да")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

/** Растёт каждый раз, когда приложение возвращается на передний план (например, из системных настроек): по нему перечитывают статусы. */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

/** «Название (пакет)» вместо голого имени пакета; если приложение не найдено или скрыто от нас — просто пакет. Результат запоминается на время показа. */
@Composable
fun appLabel(context: Context, pkg: String): String = remember(pkg) {
    try {
        val pm = context.packageManager
        val name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        if (name.isBlank() || name == pkg) pkg else "$name ($pkg)"
    } catch (_: Throwable) {
        pkg
    }
}


/** Таблица «цели × сервисы»: точка в клетке — открыт (зелёная), частично (жёлтая), закрыт (красная), не проверен (серая). */
@Composable
fun TargetMatrix(services: List<CheckService>, rows: List<MatrixRow>) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    fun dot(st: ServiceStatus) = when (st) {
        ServiceStatus.OPEN -> PhoenixTheme.ok(dark)
        ServiceStatus.PARTIAL -> PhoenixTheme.amber(dark)
        ServiceStatus.CLOSED -> scheme.error
        ServiceStatus.UNKNOWN -> scheme.outlineVariant
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
            services.forEach { s ->
                Text(s.id, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, modifier = androidx.compose.ui.Modifier.width(32.dp))
            }
        }
        rows.forEach { r ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    Text(r.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(r.server, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                r.statuses.forEach { st ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.size(12.dp).background(dot(st), androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            listOf(
                ServiceStatus.OPEN to tr("открыт"),
                ServiceStatus.PARTIAL to tr("частично"),
                ServiceStatus.CLOSED to tr("закрыт"),
                ServiceStatus.UNKNOWN to tr("не проверен"),
            ).forEach { (st, label) ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(8.dp).background(dot(st), androidx.compose.foundation.shape.CircleShape))
                    Text(" " + label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
