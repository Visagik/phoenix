package com.phoenix.app

import java.io.File

/**
 * Режим работы. Переключает пул источников и списки обхода VPN; у каждого режима свои данные
 * (источники, чёрный список, статистика, файлы конфигов, обход).
 *  - WHITELIST — режим белых списков (мобильный интернет, где открыты только разрешённые сайты);
 *  - BLACKLIST — обычный режим (блокируются отдельные сервисы).
 */
enum class Mode(val title: String, val folder: String?) {
    WHITELIST("Белые списки", null),
    BLACKLIST("Чёрные списки", "mode_bl");

    /** Папка данных режима. Белые списки живут в корне — так сохраняются данные прежних версий. */
    fun dir(root: File): File = (if (folder == null) root else File(root, folder)).also { it.mkdirs() }

    companion object {
        private const val FILE = "mode.txt"

        fun read(root: File): Mode {
            val text = try {
                File(root, FILE).takeIf { it.exists() }?.readText()?.trim()
            } catch (_: Exception) {
                null
            }
            return entries.firstOrNull { it.name == text } ?: WHITELIST
        }

        /** Растёт при каждой смене режима из любого места (служба, определитель, экран): экраны подхватывают смену сразу. */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

        fun write(root: File, mode: Mode) {
            File(root, FILE).writeTextAtomic(mode.name)
            changes.value = changes.value + 1
        }
    }
}
