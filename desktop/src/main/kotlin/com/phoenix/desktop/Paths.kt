package com.phoenix.desktop

import android.content.Context
import java.io.File

/** Где лежат данные: ~/.local/share/phoenix (или $XDG_DATA_HOME/phoenix; для проверок — свойство phoenix.dir). */
object Paths {
    val dataDir: File by lazy {
        val custom = System.getProperty("phoenix.dir")
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        val base = when {
            !custom.isNullOrBlank() -> File(custom)
            xdg != null -> File(xdg, "phoenix")
            else -> File(System.getProperty("user.home"), ".local/share/phoenix")
        }
        base.also { it.mkdirs() }
    }

    /** Ядро Xray и его базы geoip/geosite, скачанные приложением. */
    val xrayDir: File get() = File(dataDir, "xray").also { it.mkdirs() }

    /** Временные конфиги запущенного ядра (с паролями локального входа): права только владельцу, чистятся при старте. */
    val runDir: File get() = File(dataDir, "run").also { it.mkdirs() }

    /** Настройки десктопной версии. */
    val settingsFile: File get() = File(dataDir, "desktop.properties")

    /** «Контекст» для общей логики: ей нужна только папка данных. */
    val context: Context by lazy { Context(dataDir) }
}
