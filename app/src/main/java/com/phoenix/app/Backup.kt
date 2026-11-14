package com.phoenix.app

import java.io.File

class BackupFormatException(message: String) : Exception(message)

/**
 * Текстовый формат резервной копии: заголовок и разделы «[имя]» со строками. Без JSON, чтобы файл можно было
 * открыть и поправить руками. Строки, начинающиеся с «[» или «\\», экранируются обратной косой чертой.
 */
object BackupCodec {
    const val HEADER = "PHOENIX-BACKUP"
    const val VERSION = 1

    fun encode(sections: Map<String, List<String>>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append(' ').append(VERSION).append('\n')
        for ((name, lines) in sections) {
            sb.append('[').append(name).append("]\n")
            for (l in lines) {
                val line = l.replace('\r', ' ').replace('\n', ' ')
                if (line.isEmpty()) continue
                if (line.startsWith("[") || line.startsWith("\\")) sb.append('\\')
                sb.append(line).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun decode(text: String): Map<String, List<String>> {
        val lines = text.removePrefix("﻿").lines()
        val first = lines.firstOrNull { it.isNotBlank() }?.trim() ?: throw BackupFormatException("Файл пустой")
        if (!first.startsWith(HEADER)) throw BackupFormatException("Это не резервная копия Phoenix")
        val version = first.removePrefix(HEADER).trim().toIntOrNull() ?: throw BackupFormatException("Не удалось прочитать версию файла")
        if (version > VERSION) throw BackupFormatException("Копия создана более новой версией Phoenix (формат $version)")
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var started = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (!started) {
                if (line.trim() == first) started = true
                continue
            }
            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                current = out.getOrPut(line.substring(1, line.length - 1)) { ArrayList() }
                continue
            }
            if (line.isBlank()) continue
            val c = current ?: continue
            c += if (line.startsWith("\\")) line.substring(1) else line
        }
        return out
    }
}

/** Что именно попало в импорт — для сообщения пользователю. */
data class ImportReport(val settings: Map<String, String>, val notes: List<String>) {
    fun text(): String = notes.joinToString("\n")
}

/**
 * Экспорт и импорт данных приложения: настройки и по каждому режиму — цели, обход (приложения и сайты), свои источники,
 * чёрный список источников, выбранные сервисы, накопленный опыт конфигов и журнал (до 5 МБ). Сами скачанные конфиги не входят
 * (они обновляются ночным сбором). Импорт ничего не стирает: списки объединяются, у одиночных настроек берётся значение из файла.
 */
object Backup {
    /** Предел размера копии при импорте (символов). Копия с историей и журналами загрузок легко весит 7–10 МБ. */
    const val MAX_IMPORT_CHARS = 40_000_000

    private enum class How { UNION, TARGETS, REPLACE, IF_ABSENT, LOG }

    private val FILES: Map<String, How> = linkedMapOf(
        "targets.tsv" to How.TARGETS,
        "bypass_apps.txt" to How.UNION,
        "bypass_sites.txt" to How.UNION,
        "bypass_preset_off" to How.REPLACE,
        "bypass_open.txt" to How.REPLACE,
        "my_sources.txt" to How.UNION,
        "blacklist.txt" to How.UNION, // чёрный список источников только растёт: ничего из него не теряется
        "audit_keep.txt" to How.UNION,
        "list_url.txt" to How.REPLACE,
        "check_services.txt" to How.REPLACE,
        "custom_services.tsv" to How.UNION, // свои сервисы для проверки
        "hidden_services.txt" to How.UNION, // убранные пользователем сервисы
        "source_priority.tsv" to How.REPLACE, // ручные приоритеты источников
        "bypass_auto_off.txt" to How.UNION, // исключённые из автодобавленных записей обхода
        "direct_watch.tsv" to How.IF_ABSENT, // опыт прямой доступности записей обхода
        "config_history.tsv" to How.IF_ABSENT,
        "service_history.tsv" to How.IF_ABSENT, // накопленный шанс работы приложений
        "parallel_limits.tsv" to How.IF_ABSENT, // сколько параллельных проверок выдерживает каждая сеть
        "source_cadence.tsv" to How.IF_ABSENT, // «плохие дни» источников для мягкого понижения
        "activity_hours.tsv" to How.IF_ABSENT, // часы активности (когда телефоном пользуются)
        "download.log" to How.LOG, // журнал: при импорте сливается с текущим по времени (до 5 МБ)
    )

    private const val ABSENT = "-"

    private fun modeDir(root: File, mode: Mode) = mode.dir(root)

    private fun readLines(f: File): List<String> =
        try {
            if (f.exists()) f.readLines().map { it.trimEnd('\r') }.filter { it.isNotBlank() } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    /** Цели в копии без серверов (в них ключи доступа, и они всё равно перепроверяются сами). */
    private fun targetsForExport(lines: List<String>): List<String> =
        lines.map { line -> line.split('\t').take(7).joinToString("\t") }

    fun export(root: File, settings: Map<String, String>, stamp: String): String {
        val sections = LinkedHashMap<String, List<String>>()
        sections["info"] = listOf("exported=$stamp")
        sections["settings"] = settings.map { (k, v) -> "$k=$v" }
        for (mode in Mode.entries) {
            val dir = modeDir(root, mode)
            for ((name, how) in FILES) {
                val f = File(dir, name)
                var lines = if (how == How.LOG) LogTrim.tailLines(f) else readLines(f)
                if (name == "targets.tsv") lines = targetsForExport(lines)
                // «пресет российских приложений включён» хранится отсутствием файла — это тоже значение, его переносим явно
                if (name == "bypass_preset_off" && !f.exists()) lines = listOf(ABSENT)
                if (lines.isEmpty()) continue
                sections["${mode.name}:$name"] = lines
            }
        }
        return BackupCodec.encode(sections)
    }

    fun import(root: File, text: String): ImportReport {
        val sections = BackupCodec.decode(text)
        val notes = ArrayList<String>()
        val settings = LinkedHashMap<String, String>()
        sections["settings"]?.forEach { l ->
            val eq = l.indexOf('=')
            if (eq > 0) settings[l.substring(0, eq)] = l.substring(eq + 1)
        }
        if (settings.isNotEmpty()) notes += "Настройки: ${settings.size}"

        for (mode in Mode.entries) {
            val dir = modeDir(root, mode)
            val parts = ArrayList<String>()
            for ((name, how) in FILES) {
                val incoming = sections["${mode.name}:$name"] ?: continue // защита: имена файлов берём только из нашего списка
                val f = File(dir, name)
                try {
                dir.mkdirs()
                when (how) {
                    How.UNION -> {
                        val have = readLines(f)
                        val merged = (have + incoming).distinct()
                        if (merged.size != have.size) {
                            f.writeTextAtomic(merged.joinToString("\n") + "\n")
                            parts += "$name +${merged.size - have.size}"
                        }
                    }
                    How.TARGETS -> {
                        val have = readLines(f)
                        val ids = have.map { it.substringBefore('\t') }.toSet()
                        // серверы в чужой копии не принимаем (как и не отдаём при экспорте): чужая копия иначе могла бы привязать цель
                        // к заданному серверу или пустить её мимо VPN (phoenix:direct) — найдёт свои серверы перепроверка (находка Фейбла 1.8)
                        val add = targetsForExport(incoming.filter { it.substringBefore('\t') !in ids && it.split('\t').size >= 7 })
                        if (add.isNotEmpty()) {
                            f.writeTextAtomic((have + add).joinToString("\n") + "\n")
                            parts += "цели +${add.size}"
                        }
                    }
                    How.REPLACE -> {
                        if (incoming == listOf(ABSENT)) {
                            if (f.exists()) {
                                f.delete()
                                parts += "$name сброшен"
                            }
                        } else {
                            f.writeTextAtomic(incoming.joinToString("\n") + "\n")
                            parts += name
                        }
                    }
                    How.LOG -> {
                        val have = LogTrim.tailLines(f)
                        val merged = (have + incoming).distinct().sortedBy { it.substringBefore(' ') }
                        if (merged.size != have.size) {
                            f.writeTextAtomic(merged.joinToString("\n") + "\n")
                            LogTrim.trimIfNeeded(f)
                            parts += "журнал +${merged.size - have.size}"
                        }
                    }
                    How.IF_ABSENT -> {
                        if (readLines(f).isEmpty()) {
                            f.writeTextAtomic(incoming.joinToString("\n") + "\n")
                            parts += "опыт конфигов"
                        }
                    }
                }
                } catch (e: Exception) {
                    // один сбойный файл (нет места, чужой формат) не должен ломать остальной импорт: так на новом телефоне сразу после установки терялось всё
                    parts += "$name: пропущен (${e.javaClass.simpleName})"
                }
            }
            notes += "${mode.title}: " + (if (parts.isEmpty()) "нового нет" else parts.joinToString(", "))
        }
        return ImportReport(settings, notes)
    }
}
