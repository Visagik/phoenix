package com.phoenix.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.system.exitProcess

/** Значок приложения: оранжевый круг с буквой P (рисуется в коде, отдельных файлов не нужно). */
private fun makeIcon(): BitmapPainter {
    val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = java.awt.Color(0xFF, 0x8A, 0x3D)
    g.fillOval(2, 2, 60, 60)
    g.color = java.awt.Color(0x33, 0x12, 0x00)
    g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 40)
    val fm = g.fontMetrics
    g.drawString("P", (64 - fm.stringWidth("P")) / 2, (64 - fm.height) / 2 + fm.ascent)
    g.dispose()
    return BitmapPainter(img.toComposeImageBitmap())
}

fun main(args: Array<String>) {
    // phoenix --collect: без окна собрать и проверить, вывести итог и выйти (для systemd-таймера или cron)
    if ("--collect" in args) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = Engine(scope)
        engine.init()
        runBlocking { engine.refreshAll().join() }
        println(engine.message.value)
        engine.shutdown()
        exitProcess(0)
    }
    val minimized = "--minimized" in args
    application {
        val scope = rememberCoroutineScope()
        val engine = remember {
            Engine(scope).also {
                it.init()
                it.startScheduler()
                // если процесс завершат снаружи (logout, kill), системный прокси всё равно вернётся как был
                Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() })
            }
        }
        val trayOk = isTraySupported
        var visible by remember { mutableStateOf(!(minimized || engine.settings.startMinimized) || !trayOk) }
        val icon = remember { makeIcon() }
        val state = rememberWindowState(width = 1100.dp, height = 740.dp)

        fun quit() {
            engine.shutdown()
            exitApplication()
        }

        if (trayOk) {
            Tray(
                icon = icon,
                tooltip = "Phoenix",
                onAction = { visible = true },
                menu = {
                    Item("Открыть окно", onClick = { visible = true })
                    Item("Подключить лучший сервер", onClick = { engine.connectBest() })
                    Item("Отключить", onClick = { engine.disconnect() })
                    Item("Собрать и проверить", onClick = { engine.refreshAll() })
                    Separator()
                    Item("Выход", onClick = { quit() })
                },
            )
        }
        Window(
            onCloseRequest = { if (trayOk) visible = false else quit() },
            state = state,
            visible = visible,
            title = "Phoenix",
            icon = icon,
        ) {
            PhoenixWindow(engine)
        }
    }
}
