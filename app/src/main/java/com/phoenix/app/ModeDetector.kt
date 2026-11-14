package com.phoenix.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Определяет тип блокировок по тому, что открывается напрямую, и (если включено) переключает режим.
 * Приложение всегда идёт мимо собственного VPN, поэтому проверка показывает настоящую сеть оператора.
 */
object ModeDetector {
    private val voteLock = Any()

    private val muteDetect = RepeatMute()

    /** Когда прямая проверка в последний раз показала «нет сети» (0 — после этого сеть была). Нужна, чтобы не перебирать серверы без интернета. */
    @Volatile var offlineAt = 0L

    /** Последний результат определения и когда он получен (для отсрочки перебора, пока смена режима не подтвердилась). */
    @Volatile var lastType: BlockType? = null
    @Volatile var lastTypeAt = 0L

    data class Outcome(val type: BlockType, val counts: ProbeCounts, val switchedTo: Mode?, val text: String)

    /**
     * Настоящая сеть под VPN (без VPN-транспорта, с интернетом): при смене сети «сеть по умолчанию» какое-то время может быть
     * прежней (Wi-Fi уже ушёл, а процесс ещё ходит через него) или самим туннелем, и проверка покажет не то.
     */
    @Suppress("DEPRECATION")
    private fun physicalNetwork(context: Context): android.net.Network? = try {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.allNetworks?.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            ?.filter { (_, c) ->
                c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
            // проверенная системой сеть первой; из равных — Wi-Fi, потом мобильная (как в NetInfo)
            ?.sortedWith(compareByDescending<Pair<android.net.Network, android.net.NetworkCapabilities>> {
                it.second.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.thenBy { if (it.second.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) 1 else 0 })
            ?.firstOrNull()?.first
    } catch (_: Throwable) {
        null
    }

    private fun reachable(url: String, net: android.net.Network? = null): Boolean = try {
        val c = (net?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        c.connectTimeout = 4_000
        c.readTimeout = 4_000
        c.instanceFollowRedirects = false
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Phoenix)")
        val code = c.responseCode
        c.disconnect()
        code in 200..399 || code in 400..499 // любой ответ сервера значит, что сайт доступен (блокировка обрывает соединение)
    } catch (_: Exception) {
        false
    }

    fun measure(context: Context? = null): ProbeCounts {
        val urls = BlockDetector.FOREIGN + BlockDetector.RUSSIAN
        val net = context?.let { physicalNetwork(it) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(urls.map { u -> Callable { u to reachable(u, net) } }, 14, TimeUnit.SECONDS)
                .mapNotNull { f -> try { if (f.isCancelled) null else f.get() } catch (_: Exception) { null } }
                .toMap()
            return ProbeCounts(
                foreignOk = BlockDetector.FOREIGN.count { results[it] == true },
                foreignTotal = BlockDetector.FOREIGN.size,
                ruOk = BlockDetector.RUSSIAN.count { results[it] == true },
                ruTotal = BlockDetector.RUSSIAN.size,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /** Одно определение; режим меняется только после нескольких одинаковых подряд (и если включено автоопределение). */
    fun run(context: Context, need: Int = ModeDecider.NEED_IN_ROW, ignoreHold: Boolean = false): Outcome {
        val settings = AppSettings(context)
        val storage = Storage(context)
        val counts = measure(context)
        val type = BlockDetector.classify(counts)
        offlineAt = if (type == BlockType.OFFLINE) System.currentTimeMillis() else 0L
        lastType = type
        lastTypeAt = System.currentTimeMillis()

        var switched: Mode? = null
        val held = !ignoreHold && ModeHold.active(settings.manualModeAt, settings.manualModeNet, System.currentTimeMillis(), NetInfo.label(context))
        if (ignoreHold) settings.manualModeAt = 0L
        if (settings.autoMode) {
            // чтение–изменение–запись голосов под замком: определение запускают несколько мест сразу (смена сети,
            // проверка связи, кнопка), и два одновременных замера иначе засчитывались как два «подряд» (находка 3.3)
            val (vote, target) = synchronized(voteLock) {
                val r = ModeDecider.step(ModeDecider.load(storage.voteFile), type, storage.mode, need)
                ModeDecider.save(storage.voteFile, r.first)
                r
            }
            // при ручном выборе голоса копятся, но режим меняется, только если определение упорно (4 подряд) спорит с выбором
            if (target != null && (!held || ModeHold.overridden(vote.count))) {
                if (held) settings.manualModeAt = 0L
                Mode.write(context.applicationContext.filesDir, target)
                switched = target
            }
        }
        val text = "%s · зарубежных открылось %d из %d, российских %d из %d (сеть %s)".format(
            BlockDetector.title(type), counts.foreignOk, counts.foreignTotal, counts.ruOk, counts.ruTotal, NetInfo.label(context),
        ) + (switched?.let { " → режим «${it.title}»" } ?: "") +
            (if (held && settings.autoMode && ModeDecider.modeOf(type).let { it != null && it != storage.mode }) " (режим выбран вручную — не меняю)" else "")
        settings.lastDetect = text
        settings.lastDetectAt = System.currentTimeMillis()
        // та же строка подряд — не чаще раза в 30 минут (смена режима или сети меняет текст и пишется сразу)
        if (muteDetect.shouldLog("detect", text, System.currentTimeMillis())) Storage(context).log("DETECT: $text")
        return Outcome(type, counts, switched, text)
    }
}
