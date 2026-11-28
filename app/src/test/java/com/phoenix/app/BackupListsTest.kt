package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Н7: резервная копия переносит пять добавленных значений и четыре списка; старая копия читается и ничего не стирает. */
class BackupListsTest {
    private fun tmp(): File = Files.createTempDirectory("bakl").toFile().also { it.deleteOnExit() }

    @Test fun newSettingsAreInPortableSet() {
        val keys = SettingsKeys.portable(listOf("WHITELIST", "BLACKLIST")).keys
        for (k in listOf("carry_days", "tun_engine_hev", "log_size", "facts_ticker", "geo_rules_url")) assertTrue(k, k in keys)
    }

    @Test fun roundTripRestoresValuesAndLists() {
        val values = mapOf("carry_days" to "5", "tun_engine_hev" to "true", "log_size" to "2", "facts_ticker" to "true", "geo_rules_url" to "https://x.example/r.txt")
        val lists = mapOf(
            "pool_prefer" to listOf("DE", "NL"), "pool_avoid" to listOf("RU"), "pool_hidden" to listOf("US", "tcp://1.2.3.4:5"),
            "only_apps" to listOf("com.a", "com.b"),
        )
        val text = Backup.export(tmp(), values, "s", lists = lists)
        val r = Backup.import(tmp(), text)
        assertEquals(values, r.settings)
        assertEquals(lists, r.lists)
    }

    @Test fun oldBackupWithoutListSectionsLeavesListsAlone() {
        val r = Backup.import(tmp(), Backup.export(tmp(), mapOf("auto_mode" to "true"), "s"))
        assertTrue(r.lists.isEmpty())
    }

    @Test fun foreignListNamesAreIgnored() {
        val text = BackupCodec.encode(mapOf("lists:pool_avoid" to listOf("DE"), "lists:last_link" to listOf("trojan://x")))
        val r = Backup.import(tmp(), text)
        assertEquals(mapOf("pool_avoid" to listOf("DE")), r.lists)
    }

    @Test fun geoRulesUrlOnlyHttpsOrEmpty() {
        assertTrue(SettingsKeys.importValueOk("geo_rules_url", ""))
        assertTrue(SettingsKeys.importValueOk("geo_rules_url", "https://a.example/x"))
        assertFalse(SettingsKeys.importValueOk("geo_rules_url", "http://a.example/x"))
        assertFalse(SettingsKeys.importValueOk("geo_rules_url", "file:///etc/passwd"))
        assertTrue(SettingsKeys.importValueOk("language", "ru"))
    }

    /** Сторож: каждый ключ настроек «phoenix» (через AppSettings и мимо него) либо входит в копию, либо назван в NOT_IN_BACKUP с причиной. */
    @Test fun everyAppSettingsKeyHasABackupDecision() {
        val root = listOf("app/src/main/java/com/phoenix/app", "src/main/java/com/phoenix/app", "../app/src/main/java/com/phoenix/app")
            .map { File(it) }.firstOrNull { it.isDirectory } ?: throw AssertionError("исходники не найдены из " + File(".").absolutePath)
        val call = Regex("""(?:get|put|remove)[A-Za-z]*\("([a-z_0-9]+)\"""")
        val keys = HashSet<String>()
        // AppSettings: все обращения к prefs; остальные файлы, которые открывают те же настройки: все строковые ключи get*/put* в них
        keys += call.findAll(File(root, "AppSettings.kt").readText()).map { it.groupValues[1] }.filter { it != "phoenix" }
        val outside = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "AppSettings.kt" && it.readText().contains("getSharedPreferences(\"phoenix\"") }.toList()
        assertTrue("не найдены файлы, пишущие в настройки мимо AppSettings", outside.isNotEmpty())
        // без фильтра по «_»: ключ вроде "mode" мимо AppSettings тоже должен получить решение; заведомо посторонние строки — в notKeys
        val notKeys = setOf<String>() // сюда явно вписывать ложные срабатывания (getString("phoenix") и подобные)
        for (f in outside) keys += call.findAll(f.readText()).map { it.groupValues[1] }.filter { it != "phoenix" && it !in notKeys }
        assertTrue("ключи не найдены", keys.size > 40)
        val inBackup = SettingsKeys.portable(emptyList()).keys + SettingsKeys.LISTS
        val undecided = keys.filter { it !in inBackup && it !in SettingsKeys.NOT_IN_BACKUP }
        assertTrue("нет решения про копию (добавь в SettingsKeys.COMMON/SCOPED/LISTS или в NOT_IN_BACKUP с причиной): $undecided", undecided.isEmpty())
        for ((k, why) in SettingsKeys.NOT_IN_BACKUP) { assertTrue("причина для $k", why.isNotBlank()); assertFalse("$k и в копии, и в исключениях", k in inBackup) }
        for (k in listOf("vpn_restart_times", "kill_ts", "kill_ack", "kill_code", "xray_key_b64", "exit_seen_ts")) assertTrue("$k в списке исключений", k in SettingsKeys.NOT_IN_BACKUP)
        assertFalse("ключ установки не должен попасть в копию", "xray_key_b64" in inBackup)
    }
}
