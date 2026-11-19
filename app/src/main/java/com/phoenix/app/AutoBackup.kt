package com.phoenix.app

import android.content.Context
import java.io.File

/** Копия настроек и данных раз в неделю во внутреннюю память: страховка от порчи данных и случайной очистки (при удалении приложения она пропадает — для этого есть экспорт в файл). */
object AutoBackup {
    private fun dir(context: Context) = File(context.filesDir, "auto_backup")

    fun latest(context: Context): File? =
        dir(context).listFiles()?.filter { it.name.startsWith(AutoBackupPolicy.PREFIX) }?.maxByOrNull { it.name }

    /** Вызывается после сбора: если включено и прошла неделя — пишет копию без журнала и убирает лишние старые. */
    fun runIfDue(context: Context) {
        try {
            val settings = AppSettings(context)
            val now = System.currentTimeMillis()
            if (!settings.autoBackup || !AutoBackupPolicy.due(settings.lastAutoBackupAt, now)) return
            val text = Backup.export(context.filesDir, settings.exportMap(), java.time.OffsetDateTime.now().toString(), withLog = false)
            val d = dir(context)
            d.mkdirs()
            File(d, AutoBackupPolicy.fileName(java.time.LocalDate.now().toString())).writeTextAtomic(text)
            AutoBackupPolicy.toDelete(d.list()?.toList().orEmpty()).forEach { File(d, it).delete() }
            settings.lastAutoBackupAt = now
            Storage(context).log("BACKUP: автокопия сохранена (${text.length / 1024} КБ), хранятся последние ${AutoBackupPolicy.KEEP}")
        } catch (e: Throwable) {
            try { Storage(context).log("BACKUP: автокопия не удалась: ${e.message}") } catch (_: Throwable) { }
        }
    }
}
