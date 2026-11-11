package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ServiceCheckTest {
    private val good = "trojan://pw@good.example.com:443#a"
    private val dead = "trojan://pw@dead.example.com:443#b"

    private fun fakeProbe(counter: AtomicInteger? = null): (String, String) -> Long = { config, url ->
        counter?.incrementAndGet()
        when {
            config.contains("dead.example.com") -> -1L
            url.contains("claude.ai") -> -1L
            url.contains("youtube") -> 120L
            else -> 50L
        }
    }

    @Test
    fun deadConfigSkipsServiceProbes() {
        val calls = AtomicInteger()
        val checker = ServiceChecker(fakeProbe(calls))
        val r = checker.checkOne(dead, CheckServices.ALL, "2026-10-14")
        assertTrue(r.dead)
        assertEquals(1 + 3, calls.get()) // контрольный запрос и три первых сервиса (вдруг gstatic закрыт, а приложение работает)
        assertEquals("не отвечает", r.label(CheckServices.ALL))
    }

    @Test
    fun liveConfigReportsEachService() {
        val checker = ServiceChecker(fakeProbe())
        val r = checker.checkOne(good, CheckServices.ALL, "2026-10-14")
        assertFalse(r.dead)
        assertEquals("TG✓ QW✓ CL✗ YT✓ ST✓", r.label(CheckServices.ALL))
        assertEquals(120L, r.services["YT"])
    }

    @Test
    fun unsupportedLinkIsMarkedWithoutProbing() {
        val calls = AtomicInteger()
        val r = ServiceChecker(fakeProbe(calls)).checkOne("vless://id@a.com:443?type=kcp#x", CheckServices.ALL, "d")
        assertEquals(CheckServices.UNSUPPORTED, r.baselineMs)
        assertEquals(0, calls.get())
        assertEquals("не поддерживается", r.label(CheckServices.ALL))
    }

    @Test
    fun onlySelectedServicesAreProbed() {
        val calls = AtomicInteger()
        val only = CheckServices.ALL.filter { it.id == "TG" || it.id == "YT" }
        val r = ServiceChecker(fakeProbe(calls)).checkOne(good, only, "d")
        assertEquals(3, calls.get()) // контроль + 2 сервиса
        assertEquals("TG✓ YT✓", r.label(only))
    }

    @Test
    fun bulkCheckDeduplicatesAndStoresResults() {
        val file = File.createTempFile("checks", ".tsv").also { it.deleteOnExit() }
        val store = CheckStore(file)
        val results = runBlocking {
            ServiceChecker(fakeProbe(), parallel = 2).check(listOf(good, "$good-same-key-no", dead, good), CheckServices.ALL, "2026-10-14")
        }
        // good повторяется — проверяется один раз; "$good-same-key-no" — другая ссылка (ломается как trojan с другим host?) не важно
        assertTrue(results.containsKey(CheckStore.keyOf(good)))
        assertTrue(results.getValue(CheckStore.keyOf(dead)).dead)
        store.putAll(results)
        val loaded = store.load()
        assertEquals(results.getValue(CheckStore.keyOf(good)), loaded.getValue(CheckStore.keyOf(good)))
        assertEquals(CheckServices.FAILED, loaded.getValue(CheckStore.keyOf(dead)).baselineMs)
    }

    @Test
    fun keyIgnoresNameAndSelectionDefaultsToAll() {
        // у коротких имён личность — адрес, поэтому разные имена на одном адресе дают один ключ
        assertEquals(CheckStore.keyOf("trojan://pw@h:1#a"), CheckStore.keyOf("trojan://pw@h:1#b"))
        val f = File.createTempFile("sel", ".txt").also { it.delete() }
        val sel = CheckSelection(f)
        assertEquals(5, sel.selected().size)
        sel.set(listOf("TG", "ST"))
        assertEquals(listOf("TG", "ST"), sel.selected().map { it.id })
        f.delete()
    }

    @Test
    fun bypassStoreCombinesPresetAndUserApps() {
        val dir = java.nio.file.Files.createTempDirectory("bypass").toFile()
        val store = BypassStore(dir)
        assertTrue(store.effective().contains("ru.sberbankmobile"))
        store.toggle("org.example.app", true)
        assertTrue(store.effective().contains("org.example.app"))
        store.presetEnabled = false
        assertEquals(setOf("org.example.app"), store.effective())
        store.presetEnabled = true
        store.toggle("org.example.app", false)
        assertFalse(store.effective().contains("org.example.app"))
        dir.deleteRecursively()
    }

    @Test
    fun modeStoresDataSeparately() {
        val root = java.nio.file.Files.createTempDirectory("mode").toFile()
        assertEquals(Mode.WHITELIST, Mode.read(root))
        Mode.write(root, Mode.BLACKLIST)
        assertEquals(Mode.BLACKLIST, Mode.read(root))
        assertEquals(root, Mode.WHITELIST.dir(root))
        assertEquals(File(root, "mode_bl"), Mode.BLACKLIST.dir(root))
        root.deleteRecursively()
    }

    @Test
    fun sameNameOnNewServerKeepsHistoryAndIsMarkedStale() {
        val oldLink = "vless://id@1.1.1.1:443?security=tls#%F0%9F%87%A9%F0%9F%87%AA%20Wi-Fi%2010%20%7C%20%40rostunnel"
        val newLink = "vless://id@2.2.2.2:443?security=tls#%F0%9F%87%A9%F0%9F%87%AA%20Wi-Fi%2010%20%7C%20%40rostunnel"
        assertEquals(CheckStore.keyOf(oldLink), CheckStore.keyOf(newLink))
        val r = CheckResult("2026-10-14", 300, mapOf("TG" to 100L), 12.5, ConfigIdentity.address(oldLink))
        assertFalse(r.staleFor(oldLink))
        assertTrue(r.staleFor(newLink))
        assertEquals("300 мс · 12.5 Мбит/с", r.speedText())
    }

    @Test
    fun identityIgnoresFlagsGeoLabelAndCase() {
        val a = ConfigIdentity.key("vless://id@1.1.1.1:443#%F0%9F%87%A9%F0%9F%87%AA%20Wi-Fi%2010%20%7C%20%40rostunnel%20%7C%20%5BDE%5D%20TG%3Aok%20QW%3Aok%20CL%3Aok%20YT%3Aok%20ST%3Aok")
        val b = ConfigIdentity.key("vless://id@9.9.9.9:443#wi-fi 10 | @rostunnel")
        assertEquals(a, b)
        assertTrue(a.startsWith("vless|"))
        // разные протоколы с одним именем — разные конфиги
        assertFalse(a == ConfigIdentity.key("trojan://pw@9.9.9.9:443#wi-fi 10 | @rostunnel"))
    }

    @Test
    fun speedIsKeptWhenServicesAreRechecked() {
        val f = File.createTempFile("checks2", ".tsv").also { it.deleteOnExit() }
        val store = CheckStore(f)
        val link = "trojan://pw@speed.example.com:443#Long Name Server"
        val key = CheckStore.keyOf(link)
        store.putSpeed(key, SpeedResult(20.0, 150, 1_000_000, 400), "2026-10-14", ConfigIdentity.address(link))
        store.putAll(mapOf(key to CheckResult("2026-10-15", 180, mapOf("TG" to 90L), host = ConfigIdentity.address(link))))
        val r = store.load().getValue(key)
        assertEquals(20.0, r.mbps, 0.001)
        assertEquals(180L, r.baselineMs)
    }
}
