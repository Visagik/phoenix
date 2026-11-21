package com.phoenix.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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


/** Точка статуса: одна форма — один статус (● открыт, ◐ частично, ✕ закрыт, ○ не проверен), чтобы не зависеть от цвета. */
@Composable
fun StatusDot(st: ServiceStatus, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.16f
        val r = (w - stroke) / 2f
        val c = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f)
        when (st) {
            ServiceStatus.OPEN -> drawCircle(color, radius = w / 2f, center = c)
            ServiceStatus.PARTIAL -> {
                drawCircle(color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(color, startAngle = 90f, sweepAngle = 180f, useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke), size = androidx.compose.ui.geometry.Size(w - 2 * stroke, w - 2 * stroke))
            }
            ServiceStatus.CLOSED -> {
                val m = w * 0.12f
                drawLine(color, androidx.compose.ui.geometry.Offset(m, m), androidx.compose.ui.geometry.Offset(w - m, w - m), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w - m, m), androidx.compose.ui.geometry.Offset(m, w - m), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ServiceStatus.UNKNOWN -> drawCircle(color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
    }
}

/** Таблица «цели × сервисы»: точка в клетке — открыт (зелёная), частично (жёлтая), закрыт (красная), не проверен (серая). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TargetMatrix(services: List<CheckService>, rows: List<MatrixRow>) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = appDark()
    fun dot(st: ServiceStatus) = when (st) {
        ServiceStatus.OPEN -> PhoenixTheme.ok(dark)
        ServiceStatus.PARTIAL -> PhoenixTheme.amber(dark)
        ServiceStatus.CLOSED -> scheme.error
        ServiceStatus.UNKNOWN -> scheme.outline
    }
    androidx.compose.foundation.layout.BoxWithConstraints {
    // ширина столбца подстраивается: на узком экране или при многих сервисах точки сжимаются, а название цели не меньше ~110 dp
    // на главном экране не больше двух рядов по семь колонок: остальное — только в окне «Сменить сервер»
    val shown = services.take(TargetMatrixLimits.MAX)
    val colW = ((maxWidth - 110.dp) / shown.size.coerceAtMost(TargetMatrixLimits.PER_ROW).coerceAtLeast(1)).coerceIn(18.dp, 32.dp)
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        shown.chunked(TargetMatrixLimits.PER_ROW).forEachIndexed { ci, chunk ->
        val off = ci * TargetMatrixLimits.PER_ROW
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
            chunk.forEach { s ->
                Text(s.short, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, modifier = androidx.compose.ui.Modifier.width(colW))
            }
        }
        rows.forEach { r ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    Text(r.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(r.server, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                r.statuses.drop(off).take(chunk.size).forEach { st ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(colW), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        StatusDot(st, dot(st), 12.dp)
                    }
                }
            }
        }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
            listOf(
                ServiceStatus.OPEN to tr("открыт"),
                ServiceStatus.PARTIAL to tr("частично"),
                ServiceStatus.CLOSED to tr("закрыт"),
                ServiceStatus.UNKNOWN to tr("не проверен"),
            ).forEach { (st, label) ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    StatusDot(st, dot(st), 10.dp)
                    Text(" " + label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
    }
}

/**
 * Строка сервисов текущего сервера под кнопками главной: «TG ✓  YT ✓  CL ≈  ST ✗  NT ·».
 * Состояние не только цветом, но и знаком и описанием для TalkBack; на узком экране или крупном шрифте прокручивается вбок.
 */
@Composable
fun ServiceStrip(services: List<CheckService>, result: CheckResult?) {
    if (services.isEmpty()) return
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = appDark()
    val fresh = result != null && !result.dead
    // не больше двух рядов по семь; остальные цели видны только в окне «Сменить сервер»
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
        services.take(TargetMatrixLimits.MAX).chunked(TargetMatrixLimits.PER_ROW).forEach { chunk ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
                chunk.forEach { s ->
                    val st = if (fresh) result!!.status(s) else ServiceStatus.UNKNOWN
                    val (mark, color, word) = when (st) {
                        ServiceStatus.OPEN -> Triple("✓", PhoenixTheme.ok(dark), tr("открыт"))
                        ServiceStatus.PARTIAL -> Triple("≈", PhoenixTheme.amber(dark), tr("частично"))
                        ServiceStatus.CLOSED -> Triple("✗", scheme.error, tr("закрыт"))
                        ServiceStatus.UNKNOWN -> Triple("·", scheme.onSurfaceVariant, tr("не проверен"))
                    }
                    Text(
                        s.short + " " + mark,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = color,
                        maxLines = 1,
                        modifier = androidx.compose.ui.Modifier.androidxSemantics(s.title + ": " + word),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.Modifier.androidxSemantics(text: String): androidx.compose.ui.Modifier =
    this.semantics { contentDescription = text }
