package com.phoenix.app

import java.io.IOException
import java.io.InputStream

data class SpeedResult(
    /** Мегабит в секунду на скачивании */
    val mbps: Double,
    /** Время до первого байта, мс (настоящая задержка запроса через конфиг, не ping) */
    val ttfbMs: Long,
    val bytes: Long,
    val millis: Long,
)

/**
 * Измерение скорости скачивания. Время считается после первого блока данных, чтобы задержка
 * соединения не занижала скорость. Источник данных передаётся функцией — логика проверяется без сети.
 */
object SpeedTest {
    const val MAX_MS = 6_000L
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MIN_BYTES = 64L * 1024

    /** Файлы для замера: первый — Cloudflare, запасной — OVH. */
    val URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=10000000",
        "https://proof.ovh.net/files/10Mb.dat",
    )

    fun measure(
        open: () -> InputStream,
        maxMs: Long = MAX_MS,
        maxBytes: Long = MAX_BYTES,
        nanoTime: () -> Long = System::nanoTime,
    ): SpeedResult? {
        val t0 = nanoTime()
        val stream = open()
        return stream.use { input ->
            val buf = ByteArray(64 * 1024)
            val first = input.read(buf)
            if (first <= 0) return@use null
            val ttfb = (nanoTime() - t0) / 1_000_000
            val start = nanoTime()
            var total = 0L
            try {
                while (total < maxBytes && (nanoTime() - start) / 1_000_000 < maxMs) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
            } catch (_: IOException) {
                // оборвалось на середине — считаем по тому, что успело прийти
            }
            val ms = (nanoTime() - start) / 1_000_000
            if (total < MIN_BYTES || ms <= 0) return@use null
            SpeedResult(total * 8.0 / (ms / 1000.0) / 1_000_000.0, ttfb, total, ms)
        }
    }

    fun format(mbps: Double): String = if (mbps < 0) "—" else "%.1f Мбит/с".format(java.util.Locale.US, mbps)
}
