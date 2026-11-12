package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nDictionariesTest {
    private fun dir(): java.io.File? =
        listOf("app/src/main/assets/i18n", "src/main/assets/i18n", "../app/src/main/assets/i18n")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory }

    @Test fun everyDictionaryCoversEveryKeyWithSamePlaceholders() {
        val d = dir() ?: throw AssertionError("папка словарей не найдена из " + java.io.File(".").absolutePath)
        val keys = Tr.parse(d.resolve("keys.txt").readLines().joinToString("\n") { it + "\t-" }).keys
        assertTrue("ключей мало: ${keys.size}", keys.size > 300)
        for (lang in Tr.SUPPORTED) {
            val f = d.resolve("$lang.tsv")
            assertTrue("нет словаря $lang", f.exists())
            val m = Tr.parse(f.readText())
            for (k in keys) {
                val v = m[k]
                assertTrue("[$lang] нет перевода: ${k.take(40)}", v != null)
                assertEquals("[$lang] число подстановок: ${k.take(40)}", Tr.placeholders(k).size, Tr.placeholders(v!!).size)
            }
        }
    }

    @Test fun spotChecksCatchMisalignedDictionaries() {
        val d = dir() ?: throw AssertionError("папка словарей не найдена")
        val en = Tr.parse(d.resolve("en.tsv").readText())
        assertEquals("Collect now", en["Собрать сейчас"])
        assertEquals("Servers", en["Серверы"])
        assertEquals("Settings", en["Настройки"])
        assertEquals("Unpin", en["Открепить"])
        assertEquals("Servers: {} · checked alive: {}", en["Серверов: {} · проверено живых: {}"])
        assertEquals("Собрати зараз".length > 0, true)
        val uk = Tr.parse(d.resolve("uk.tsv").readText())
        assertEquals("Зібрати зараз", uk["Собрать сейчас"])
        val zh = Tr.parse(d.resolve("zh.tsv").readText())
        assertEquals("立即收集", zh["Собрать сейчас"])
        val ar = Tr.parse(d.resolve("ar.tsv").readText())
        assertEquals("اجمع الآن", ar["Собрать сейчас"])
    }
}
