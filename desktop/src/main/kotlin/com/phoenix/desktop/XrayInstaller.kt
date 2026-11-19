package com.phoenix.desktop

import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/** Поиск и установка ядра Xray для Linux. Ядро берётся с GitHub (XTLS/Xray-core) и проверяется по SHA-256 из релиза. */
object XrayInstaller {
    const val ASSET = "Xray-linux-64.zip"
    /** Прямые ссылки на последний выпуск (без API GitHub: у него жёсткий лимит запросов без входа). */
    const val ZIP_URL = "https://github.com/XTLS/Xray-core/releases/latest/download/$ASSET"
    const val DGST_URL = "$ZIP_URL.dgst"
    private val WANTED = setOf("xray", "geoip.dat", "geosite.dat")

    /** Исполняемый файл ядра: сначала скачанный приложением, потом системный (из PATH). */
    fun find(): File? {
        val own = File(Paths.xrayDir, "xray")
        if (own.isFile && own.canExecute()) return own
        val path = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
        for (dir in path) {
            val f = File(dir, "xray")
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    /** Версия установленного ядра (первая строка `xray version`) или null. */
    fun version(bin: File): String? = try {
        val p = ProcessBuilder(bin.absolutePath, "version").redirectErrorStream(true).start()
        val line = p.inputStream.bufferedReader().readLine()
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        line?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** SHA-256 архива из файла .dgst (строка вида «SHA2-256= <64 hex>») или null. */
    fun parseSha256(dgst: String): String? =
        Regex("(?im)^\\s*SHA2?-?256\\s*=\\s*([0-9a-f]{64})\\s*$").find(dgst)?.groupValues?.get(1)?.lowercase()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Достаёт из архива только xray, geoip.dat и geosite.dat (по имени файла, без путей из архива).
     * @return сколько файлов распаковано
     */
    fun unpack(zip: InputStream, into: File): Int {
        into.mkdirs()
        var count = 0
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (name !in WANTED || e.name != name) continue // вложенные пути пропускаем
                val tmp = File(into, "$name.part")
                tmp.outputStream().use { z.copyTo(it) }
                Files.move(tmp.toPath(), File(into, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                count++
            }
        }
        File(into, "xray").setExecutable(true, false)
        return count
    }

    private fun client() = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

    private fun text(c: HttpClient, url: String): String {
        val r = c.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "Phoenix-Desktop").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (r.statusCode() != 200) error("HTTP ${r.statusCode()} для $url")
        return r.body()
    }

    /** Скачивает последнюю версию ядра и ставит её в папку данных. Бросает исключение с понятным текстом при любой неудаче. */
    fun install(progress: (String) -> Unit = {}): String {
        val c = client()
        progress("Скачиваю контрольную сумму…")
        val expected = parseSha256(text(c, DGST_URL)) ?: error("не разобрал контрольную сумму из файла .dgst — без проверки ядро не ставлю")
        progress("Скачиваю ядро Xray (около 16 МБ)…")
        val tmp = File(Paths.xrayDir, "download.zip")
        val resp = c.send(
            HttpRequest.newBuilder(URI(ZIP_URL)).timeout(Duration.ofMinutes(5)).header("User-Agent", "Phoenix-Desktop").build(),
            HttpResponse.BodyHandlers.ofFile(tmp.toPath()),
        )
        if (resp.statusCode() != 200) {
            tmp.delete()
            error("HTTP ${resp.statusCode()} при скачивании ядра")
        }
        val actual = sha256(tmp)
        if (actual != expected) {
            tmp.delete()
            error("контрольная сумма ядра не совпала — файл не используется")
        }
        progress("Устанавливаю…")
        val n = tmp.inputStream().use { unpack(it, Paths.xrayDir) }
        tmp.delete()
        if (n < 3) error("в архиве не хватает файлов (распаковано $n из 3)")
        return find()?.let { version(it) } ?: "установлено"
    }
}
