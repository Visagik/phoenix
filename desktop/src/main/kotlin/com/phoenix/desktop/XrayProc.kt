package com.phoenix.desktop

import com.phoenix.app.Json
import com.phoenix.app.SocksAuth
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedDeque

/** Свободный порт на localhost. */
object Ports {
    fun free(): Int = ServerSocket(0).use { it.localPort }

    fun open(port: Int, timeoutMs: Int = 200): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }
}

object ConfigInject {
    /**
     * Добавляет в конфиг без входов (probeConfig) локальный SOCKS-вход с паролем: через него приложение ходит в сеть «через сервер».
     * Конфиг, у которого уже есть inbounds, не трогаем — вызывающий код сам отвечает за входы.
     */
    fun socksInbound(config: String, port: Int, auth: SocksAuth): String {
        if (config.contains("\"inbounds\"")) return config
        val inbound = mapOf(
            "tag" to "in",
            "listen" to "127.0.0.1",
            "port" to port,
            "protocol" to "socks",
            "settings" to mapOf("auth" to "password", "accounts" to listOf(mapOf("user" to auth.user, "pass" to auth.pass)), "udp" to false),
        )
        val trimmed = config.trimStart()
        require(trimmed.startsWith("{")) { "конфиг не похож на JSON-объект" }
        return "{\"inbounds\":" + Json.write(listOf(inbound)) + (if (trimmed.drop(1).trimStart().startsWith("}")) "" else ",") + trimmed.drop(1)
    }
}

/** Один запущенный процесс Xray. Конфиг лежит во временном файле с правами владельца и удаляется при остановке. */
class XrayProc(private val bin: File, private val config: String) {
    private var process: Process? = null
    private var cfgFile: File? = null
    private val tail = ConcurrentLinkedDeque<String>()

    val isRunning: Boolean get() = process?.isAlive == true

    /** Последние строки вывода ядра (для сообщения об ошибке). */
    fun output(): String = tail.joinToString("\n")

    /** Запускает ядро и ждёт, пока откроется [port] (или ядро завершится). */
    fun start(port: Int, timeoutMs: Long = 6_000): Boolean {
        stop()
        val f = File(Paths.runDir, "xray-" + System.nanoTime() + ".json")
        try {
            Files.createFile(f.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            f.createNewFile()
        }
        f.writeText(config)
        cfgFile = f
        val pb = ProcessBuilder(bin.absolutePath, "run", "-c", f.absolutePath).redirectErrorStream(true)
        pb.environment()["XRAY_LOCATION_ASSET"] = Paths.xrayDir.absolutePath
        val p = try {
            pb.start()
        } catch (e: Exception) {
            tail.add("не удалось запустить: ${e.message}")
            cleanup()
            return false
        }
        process = p
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    tail.add(line)
                    while (tail.size > 20) tail.pollFirst()
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) break
            if (Ports.open(port)) return true
            Thread.sleep(40)
        }
        val ok = p.isAlive && Ports.open(port)
        if (!ok) stop()
        return ok
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            try {
                if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            } catch (_: InterruptedException) {
                p.destroyForcibly()
            }
        }
        process = null
        cleanup()
    }

    private fun cleanup() {
        cfgFile?.delete()
        cfgFile = null
    }

    companion object {
        /** Удаляет остатки конфигов от прошлых запусков (если приложение закрыли аварийно). */
        fun sweepRunDir() {
            Paths.runDir.listFiles()?.forEach { if (it.name.startsWith("xray-")) it.delete() }
        }
    }
}
