package com.phoenix.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Окно после обновления: пункты текущей версии, один раз. «Все версии» открывает «Ещё → Что нового». */
@Composable
fun WhatsNewDialog(version: String, onClose: () -> Unit, onAll: () -> Unit) {
    val entry = WhatsNew.forVersion(version) ?: run { onClose(); return }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Что нового в {}", entry.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.items.forEach { Text("•  " + tr(it), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Понятно")) } },
        dismissButton = { TextButton(onClick = onAll) { Text(tr("Все версии")) } },
    )
}

/** «Ещё → Что нового»: все версии, новые сверху. */
@Composable
fun WhatsNewPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WhatsNew.ENTRIES.forEach { entry ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Версия {}", entry.version), style = MaterialTheme.typography.titleMedium)
                    entry.items.forEach { Text("•  " + tr(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
