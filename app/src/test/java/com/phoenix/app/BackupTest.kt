package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupTest {
    private fun tmp(): File = Files.createTempDirectory("bak").toFile().also { it.deleteOnExit() }

    private fun target(id: String, servers: String = "") =
        listOf(id, "APP", id.removePrefix("APP:"), "Имя", "https://x.com/", "AUTO", "2026-10-14") .joinToString("\t") + (if (servers.isEmpty()) "" else "\t$servers")

    @Test
    fun codecRoundTripAndEscaping() {
        val sections = linkedMapOf("a" to listOf("one", "[tricky]", "\\slash", "plain=1"), "b:c.txt" to listOf("x"))
        val back = BackupCodec.decode(BackupCodec.encode(sections))
        assertEquals(sections, back)
    }

    @Test
    fun codecRejectsForeignAndNewerFiles() {
        for (bad in listOf("", "hello", "PHOENIX-BACKUP 99\n[a]\nx")) {
            try {
                BackupCodec.decode(bad); throw AssertionError("должно быть отказано: $bad")
            } catch (_: BackupFormatException) {
            }
        }
    }

    @Test
    fun exportImportMovesEverythingBetweenInstalls() {
        val a = tmp()
        Mode.WHITELIST.dir(a).let {
            File(it, "targets.tsv").writeText(target("APP:org.telegram.messenger", "trojan://pw@1.1.1.1:443#S1") + "\n")
            File(it, "bypass_apps.txt").writeText("com.a\ncom.b\n")
            File(it, "blacklist.txt").writeText("https://bad.example/1\n")
        }
        Mode.BLACKLIST.dir(a).let {
            File(it, "bypass_sites.txt").writeText("example.org\n")
            File(it, "bypass_open.txt").writeText("off")
            File(it, "my_sources.txt").writeText("https://my.example/list.txt\n")
        }
        val text = Backup.export(a, mapOf("auto_mode" to "false", "ipv6_mode" to "BLOCK"), "2026-10-15")
        assertFalse("ключи доступа серверов не должны попасть в файл", text.contains("trojan://pw@1.1.1.1"))
        assertTrue(text.contains("APP:org.telegram.messenger"))

        val b = tmp()
        val report = Backup.import(b, text)
        assertEquals("false", report.settings["auto_mode"])
        assertEquals(listOf("com.a", "com.b"), File(Mode.WHITELIST.dir(b), "bypass_apps.txt").readLines())
        assertEquals("https://bad.example/1", File(Mode.WHITELIST.dir(b), "blacklist.txt").readLines().single())
        assertEquals("example.org", File(Mode.BLACKLIST.dir(b), "bypass_sites.txt").readLines().single())
        assertEquals("off", File(Mode.BLACKLIST.dir(b), "bypass_open.txt").readText().trim())
        val t = TargetStore(File(Mode.WHITELIST.dir(b), "targets.tsv")).load().single()
        assertEquals("APP:org.telegram.messenger", t.id)
        assertTrue(t.servers.isEmpty())
    }

    @Test
    fun importMergesAndNeverShrinksBlacklist() {
        val a = tmp()
        File(Mode.WHITELIST.dir(a), "blacklist.txt").writeText("https://one.example\nhttps://two.example\n")
        File(Mode.WHITELIST.dir(a), "targets.tsv").writeText(target("APP:com.x") + "\n")
        val text = Backup.export(a, emptyMap(), "s")

        val b = tmp()
        File(Mode.WHITELIST.dir(b), "blacklist.txt").writeText("https://three.example\n")
        File(Mode.WHITELIST.dir(b), "targets.tsv").writeText(target("APP:com.y") + "\n")
        Backup.import(b, text)
        val bl = File(Mode.WHITELIST.dir(b), "blacklist.txt").readLines().toSet()
        assertEquals(setOf("https://one.example", "https://two.example", "https://three.example"), bl)
        val ids = TargetStore(File(Mode.WHITELIST.dir(b), "targets.tsv")).load().map { it.id }.toSet()
        assertEquals(setOf("APP:com.x", "APP:com.y"), ids)
        // повторный импорт ничего не дублирует
        Backup.import(b, text)
        assertEquals(3, File(Mode.WHITELIST.dir(b), "blacklist.txt").readLines().size)
    }

    @Test
    fun presetFlagTravelsInBothDirections() {
        val a = tmp() // пресет российских приложений включён (нет файла)
        val text = Backup.export(a, emptyMap(), "s")
        val b = tmp()
        File(Mode.WHITELIST.dir(b), "bypass_preset_off").writeText("off")
        Backup.import(b, text)
        assertTrue(BypassStore(Mode.WHITELIST.dir(b), Mode.WHITELIST).presetEnabled)
        val c = tmp()
        File(Mode.WHITELIST.dir(c), "bypass_preset_off").writeText("off")
        val d = tmp()
        Backup.import(d, Backup.export(c, emptyMap(), "s"))
        assertFalse(BypassStore(Mode.WHITELIST.dir(d), Mode.WHITELIST).presetEnabled)
    }

    @Test
    fun userChoicesOfRecentVersionsTravel() {
        val a = tmp()
        Mode.WHITELIST.dir(a).let {
            File(it, "hidden_services.txt").writeText("netflix\n")
            File(it, "bypass_auto_off.txt").writeText("com.vk.x\n")
            File(it, "source_priority.tsv").writeText("https://s/1\t2\n")
        }
        val b = tmp()
        Backup.import(b, Backup.export(a, emptyMap(), "s"))
        val d = Mode.WHITELIST.dir(b)
        assertEquals("netflix", File(d, "hidden_services.txt").readText().trim())
        assertEquals("com.vk.x", File(d, "bypass_auto_off.txt").readText().trim())
        assertEquals("https://s/1\t2", File(d, "source_priority.tsv").readText().trim())
    }

    @Test
    fun importOnlyTouchesKnownFiles() {
        val b = tmp()
        val evil = "PHOENIX-BACKUP 1\n[WHITELIST:../../evil.txt]\nx\n[WHITELIST:mode.txt]\nBLACKLIST\n"
        Backup.import(b, evil)
        assertFalse(File(b, "mode.txt").exists())
        assertFalse(File(b.parentFile, "evil.txt").exists())
    }
}
