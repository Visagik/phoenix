package com.phoenix.app

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Запись аварий приложения. Если процесс падает (исключение, которое никто не поймал), причина пишется в crash.log,
 * а при следующем запуске попадает в журнал строками «CRASH: …» — без неё про падение на чужом телефоне ничего не узнать.
 */
object CrashLog {
    const val FILE = "crash.log"
    const val MAX_CHARS = 12_000

    /** Текст одной аварии: время, поток и стек (причины тоже), обрезанный до разумного размера. */
    fun format(threadName: String, e: Throwable, nowIso: String): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().trim()
        val text = "$nowIso поток=$threadName\n$trace"
        return if (text.length > 6_000) text.take(6_000) + "\n…" else text
    }

    /** Дописывает аварию в файл; файл не растёт выше [MAX_CHARS] (старое отрезается). */
    fun record(dir: File, text: String) {
        val f = File(dir, FILE)
        val old = try { if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }
        val merged = (old + (if (old.isEmpty()) "" else "\n---\n") + text)
        AtomicWrite.write(f, (if (merged.length > MAX_CHARS) merged.takeLast(MAX_CHARS) else merged).toByteArray(Charsets.UTF_8))
    }

    /** Забирает записанные аварии (и удаляет файл), либо null, если падений не было. */
    fun take(dir: File): String? {
        val f = File(dir, FILE)
        if (!f.exists()) return null
        val t = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        return t.ifBlank { null }
    }

    /** Строки для журнала: по одной на строку стека, не больше [maxLines]. */
    fun logLines(text: String, maxLines: Int = 40): List<String> =
        text.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.take(maxLines).map { "CRASH: $it" }.toList()

    fun install(dir: File) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { record(dir, format(t.name, e, java.time.Instant.now().toString())) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
