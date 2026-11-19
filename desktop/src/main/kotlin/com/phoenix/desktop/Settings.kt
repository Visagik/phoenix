package com.phoenix.desktop

import com.phoenix.app.Mode
import java.io.File
import java.util.Properties

/** Настройки десктопной версии: простой файл .properties в папке данных. */
class DesktopSettings(private val file: File = Paths.settingsFile) {
    private val props = Properties()

    init {
        try {
            if (file.exists()) file.inputStream().use { props.load(it) }
        } catch (_: Exception) {
        }
    }

    private fun str(k: String, d: String) = props.getProperty(k) ?: d
    private fun int(k: String, d: Int) = props.getProperty(k)?.toIntOrNull() ?: d
    private fun bool(k: String, d: Boolean) = props.getProperty(k)?.let { it == "true" } ?: d

    @Synchronized
    private fun put(k: String, v: String) {
        props.setProperty(k, v)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { props.store(it, "Phoenix desktop") }
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == str("mode", "") } ?: Mode.BLACKLIST
        set(v) = put("mode", v.name)

    /** Как часто собирать автоматически, часов (0 — выключено). */
    var intervalHours: Int
        get() = int("intervalHours", 6).coerceIn(0, 72)
        set(v) = put("intervalHours", v.coerceIn(0, 72).toString())

    var lastRunMs: Long
        get() = props.getProperty("lastRunMs")?.toLongOrNull() ?: 0L
        set(v) = put("lastRunMs", v.toString())

    var socksPort: Int
        get() = int("socksPort", 10808)
        set(v) = put("socksPort", v.coerceIn(1024, 65535).toString())

    var httpPort: Int
        get() = int("httpPort", 10809)
        set(v) = put("httpPort", v.coerceIn(1024, 65535).toString())

    /** Включать системный прокси (gsettings) при подключении. */
    var systemProxy: Boolean
        get() = bool("systemProxy", true)
        set(v) = put("systemProxy", v.toString())

    /** Сама переключаться на другой сервер, если текущий перестал отвечать. */
    var autoFailover: Boolean
        get() = bool("autoFailover", true)
        set(v) = put("autoFailover", v.toString())

    /** После автосбора подключаться к лучшему серверу, если подключения ещё нет. */
    var autoConnect: Boolean
        get() = bool("autoConnect", false)
        set(v) = put("autoConnect", v.toString())

    /** Проверять выбранные сервисы после сбора. */
    var checkServices: Boolean
        get() = bool("checkServices", true)
        set(v) = put("checkServices", v.toString())

    var parallel: Int
        get() = int("parallel", 6).coerceIn(1, 16)
        set(v) = put("parallel", v.coerceIn(1, 16).toString())

    /** Домены, которые идут напрямую, мимо сервера (по одному в строке). */
    var bypass: List<String>
        get() = str("bypass", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = put("bypass", v.joinToString(","))

    /** Язык интерфейса (пусто — язык системы). */
    var lang: String
        get() = str("lang", "")
        set(v) = put("lang", v)

    var startMinimized: Boolean
        get() = bool("startMinimized", false)
        set(v) = put("startMinimized", v.toString())
}
