package com.phoenix.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож API для Lite (Android 6, API 23). Тесты идут на обычной Java, где есть все классы, поэтому падение на старом Android они не ловят:
 * так прошли CompletableFuture (API 24) и restrictBackgroundStatus (API 24).
 *
 * ЧТО ДЕЛАТЬ, ЕСЛИ ТЕСТ УПАЛ:
 *  - класс из BANNED: заменить на доступный с API 23 (например CountDownLatch вместо CompletableFuture, свой B64 вместо java.util.Base64).
 *    Сверять с таблицей desugaring Google (core library desugaring подменяет не всё: java.util.concurrent почти не покрыт).
 *  - вызов из GUARDED: обернуть в `if (Build.VERSION.SDK_INT >= N)` в той же функции (или на 5 строк выше) либо пометить функцию @RequiresApi(N)
 *    и вызывать её только после проверки. Если lint (lintLiteDebug) нашёл новое место NewApi — допишите его вызов в GUARDED.
 * Правило приблизительное: проверяется наличие SDK_INT рядом, а не его правильность.
 */
class ApiGuardTest {
    /** Классы, которых нет на API 23 и которые desugaring не подменяет. */
    private val banned = listOf("CompletableFuture", "CompletionStage", "java.util.Base64", "java.nio.file")

    /** Вызовы новее API 23: нужна проверка SDK_INT рядом. */
    private val guarded = listOf(
        "restrictBackgroundStatus", "ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS", "getConnectionOwnerUid", "getHistoricalProcessExitReasons",
        "currentThermalStatus", "NotificationChannel(", "MediaStore.Downloads", "unsafeCheckOpNoThrow", "longVersionCode", "setMetered", "isRequestPinAppWidgetSupported", "requestPinAppWidget(", "STOP_FOREGROUND_REMOVE", "getPackageUid", "endpointIdentificationAlgorithm", "contentLengthLong", "signingInfo", "stopReason",
    )

    private fun sources(): List<File> =
        File("app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also { assertTrue("не найдены исходники (тест запускается из корня проекта)", it.isNotEmpty()) }

    private fun isComment(l: String): Boolean = l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** Строки функции moveNio в AtomicWrite.kt: единственное разрешённое место java.nio.file (вызывается только с API 26). */
    private fun inMoveNio(f: File, lines: List<String>, i: Int): Boolean {
        if (f.name != "AtomicWrite.kt") return false
        val start = (i downTo 0).firstOrNull { lines[it].contains("fun moveNio") } ?: return false
        return i - start <= 8
    }

    @Test fun noClassesMissingOnApi23() {
        val bad = ArrayList<String>()
        for (f in sources()) {
            val lines = f.readLines()
            for ((i, l) in lines.withIndex()) {
                if (isComment(l)) continue
                val hit = banned.firstOrNull { l.contains(it) } ?: continue
                if (inMoveNio(f, lines, i)) continue
                bad += "${f.name}:${i + 1} — $hit"
            }
        }
        assertTrue("классы, которых нет на Android 6:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun newApiCallsAreGuarded() {
        val bad = ArrayList<String>()
        for (f in sources()) {
            val lines = f.readLines()
            for ((i, l) in lines.withIndex()) {
                if (isComment(l)) continue
                val hit = guarded.firstOrNull { l.contains(it) } ?: continue
                val funStart = (i downTo 0).firstOrNull { lines[it].contains("fun ") } ?: 0
                val from = minOf(funStart, maxOf(0, i - 5))
                val window = lines.subList(from, i + 1)
                // страховка: аннотация @RequiresApi над функцией считается проверкой (вызывающий обязан проверить сам)
                val annotated = (maxOf(0, funStart - 3)..funStart).any { lines[it].contains("RequiresApi") }
                if (window.none { it.contains("SDK_INT") } && !annotated) bad += "${f.name}:${i + 1} — $hit"
            }
        }
        assertTrue("вызовы новее API 23 без проверки SDK_INT:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
