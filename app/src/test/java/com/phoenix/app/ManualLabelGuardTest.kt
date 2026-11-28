package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** О1: подпись ручного запуска записана ровно один раз (`const val MANUAL` в Recheck.kt); везде остальное — `Recheck.isManual` / `Recheck.MANUAL`. */
class ManualLabelGuardTest {
    private fun srcDir(): java.io.File? =
        listOf("app/src/main/java/com/phoenix/app", "src/main/java/com/phoenix/app", "../app/src/main/java/com/phoenix/app")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory }

    @Test fun manualLabelLiteralAppearsOnlyInTheConstant() {
        val dir = srcDir() ?: throw AssertionError("исходники не найдены из " + java.io.File(".").absolutePath)
        val hits = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (line.contains("\"вручную\"")) f.name + ":" + (i + 1) + ": " + line.trim() else null }
        }.toList()
        assertEquals("литерал подписи только в объявлении константы: $hits", 1, hits.size)
        assertTrue(hits.single(), hits.single().startsWith("Recheck.kt:") && hits.single().contains("const val MANUAL"))
    }
}
