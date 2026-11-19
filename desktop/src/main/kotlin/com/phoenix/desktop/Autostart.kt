package com.phoenix.desktop

import java.io.File

/** Автозапуск при входе в систему: файл ~/.config/autostart/phoenix.desktop (работает в Cinnamon, GNOME, KDE, XFCE). */
object Autostart {
    private val file: File get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "autostart/phoenix.desktop")

    /** Как запускать приложение: из установленного пакета (jpackage) или из переменной PHOENIX_LAUNCHER; null — неизвестно. */
    fun launcher(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: System.getenv("PHOENIX_LAUNCHER")?.takeIf { it.isNotBlank() }

    fun entry(exec: String): String = """
        [Desktop Entry]
        Type=Application
        Name=Phoenix
        Comment=Сбор и проверка VPN-конфигов
        Exec=${quote(exec)} --minimized
        Terminal=false
        X-GNOME-Autostart-enabled=true
    """.trimIndent() + "\n"

    /** В значении Exec пробелы и спецсимволы экранируются двойными кавычками. */
    fun quote(path: String): String =
        if (path.any { it == ' ' || it == '"' || it == '\\' || it == '$' || it == '`' }) "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`") + "\"" else path

    fun isEnabled(): Boolean = file.isFile

    /** @return null — готово, иначе текст ошибки */
    fun set(enabled: Boolean): String? {
        if (!enabled) {
            file.delete()
            return null
        }
        val exec = launcher() ?: return "Автозапуск работает у установленного пакета (.deb). При запуске через Gradle задайте путь к запуску в переменной PHOENIX_LAUNCHER."
        return try {
            file.parentFile.mkdirs()
            file.writeText(entry(exec))
            null
        } catch (e: Exception) {
            "Не удалось записать ${file.path}: ${e.message}"
        }
    }
}
