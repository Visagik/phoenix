package com.phoenix.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Бегущая строка вверху главного экрана: один факт о Phoenix за раз; если не помещается — едет по кругу. Нажатие — следующий факт. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FactTicker() {
    var index by remember { mutableIntStateOf(Facts.pick(Math.random())) }
    Text(
        tr(Facts.ALL[index]),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.fillMaxWidth().clickable { index = Facts.next(index) }.padding(horizontal = 16.dp, vertical = 8.dp)
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, velocity = 40.dp),
    )
}
