package com.phoenix.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class Found(val cand: Candidate, val ms: Long)

/** Задержка для журнала: 0 мс у заранее найденного запасного сервера — не измерение, а заглушка. */
fun msText(ms: Long): String = if (ms > 0) "$ms мс" else "задержка не измерялась, найден заранее"

/**
 * Быстрый поиск рабочих серверов среди сотен конфигов («сначала задержка, потом точнее»).
 *
 * Для каждого конфига по очереди рейтинга: (1) обычное TCP-соединение с сервером — мёртвые отсеиваются за пару секунд
 * и не запускают ядро; (2) настоящая задержка через ядро (HTTPS-запрос к [url] через этот конфиг). Обе стадии идут
 * параллельно и конвейером: пока одни серверы ещё проверяются по TCP, у других уже меряется задержка через ядро.
 * Как только найдено [want] рабочих, поиск прекращается — не нужно ждать конца очереди. Точные проверки
 * (сервисы, скорость) делаются позже и только для найденных.
 *
 * [probe] возвращает мс или отрицательное число; [reach] — IP сервера или null, если TCP не открылся.
 */
class ServerSearch(
    private val reach: (String, Int) -> String?,
    private val probe: (config: String, url: String) -> Long,
    private val tcpParallel: Int = 48,
    private val probeParallel: Int = 12,
    private val maxCandidates: Int = 600,
    private val url: String = CheckServices.BASELINE_URL,
    /** Проверять только лучший конфиг каждого сервера (для поиска запасных) или все (для полной перепроверки). */
    private val onePerHost: Boolean = true,
    /** Замер времени ответа шлюза (роутера) в мс или -1: если шлюз тормозит, параллельность снижается. null — не следить. */
    private val loadSample: (() -> Long)? = null,
    /** Внешняя причина остановить поиск (сменилась сеть или режим, поиск бесперспективен); проверяется дважды в секунду. */
    private val shouldStop: (() -> Boolean)? = null,
    /** Сколько серверов подряд без единого открытого TCP считать «нет интернета» (на мобильной сети меньше — см. [offlineLimit]). */
    private val offlineSuspect: Int = OFFLINE_SUSPECT,
    /** Сколько проб подряд без единого ответа на одном host:port, после которых остальные конфиги этого адреса пропускаются (0 — не пропускать). */
    private val hostStrikes: Int = 0,
) {
    companion object {
        /** Сервер не открыл TCP-соединение (в этой сети он недоступен; в другой может быть жив). */
        const val TCP_FAIL = -3L

        /** Пробу не делали: на этом host:port уже несколько конфигов подряд не дали интернета, а ни один не сработал. */
        const val HOST_SKIPPED = -4L

        /** Столько серверов подряд без единого открытого TCP — почти наверняка нет интернета, а не умерли все серверы. */
        const val OFFLINE_SUSPECT = 120

        /** …и не меньше стольких разных host:port (несколько конфигов одного сервера не должны давать «нет интернета»). */
        const val OFFLINE_MIN_HOSTS = 40

        /** На мобильной сети при плохом покрытии каждая проба стоит батареи: останавливаемся раньше (раньше везде было 120, по журналу 150–290 проб за раз). */
        const val OFFLINE_SUSPECT_MOBILE = 50

        fun offlineLimit(mobile: Boolean): Int = if (mobile) OFFLINE_SUSPECT_MOBILE else OFFLINE_SUSPECT
    }

    /** Поиск остановлен: [OFFLINE_SUSPECT] серверов подряд не открыли TCP, ни один не открылся. */
    @Volatile var suspectedOffline = false
        private set

    /** Поиск прерван по [shouldStop]. */
    @Volatile var aborted = false
        private set

    /** Итоги последнего поиска: сколько хостов прошли TCP-проверку, сколько не открылись, сколько запусков ядра. */
    @Volatile var triedTcp = 0
        private set
    @Volatile var tcpFailed = 0
        private set
    @Volatile var probed = 0
        private set
    @Volatile var hostSkipped = 0
        private set

    /** Суммарное время проверок (по всем потокам, мс): TCP и пробы через ядро; для журнала, чтобы видеть, где уходит время. */
    @Volatile var tcpMsTotal = 0L
        private set
    @Volatile var probeMsTotal = 0L
        private set
    /** Время и число проб, давших ответ (живые): отдельно от мёртвых, чтобы видеть, во что обходится таймаут. */
    @Volatile var probeOkMsTotal = 0L
        private set
    @Volatile var probeOkCount = 0
        private set

    /** Сколько раз поиск притормаживал из-за перегрузки шлюза (для журнала). */
    @Volatile var throttled = 0
        private set

    /**
     * @param candidates уже упорядоченные по рейтингу; на один хост берётся первый (лучший)
     * @param onProbe итог по каждому проверенному конфигу: мс (≥0), -1 (ядро не отработало), [TCP_FAIL] или -2 (не поддерживается)
     * @return найденные рабочие, быстрейшие первыми
     */
    suspend fun find(
        candidates: List<Candidate>,
        want: Int,
        budgetMs: Long,
        onProbe: (Candidate, Long) -> Unit = { _, _ -> },
    ): List<Found> {
        val ordered = (if (onePerHost) candidates.distinctBy { it.host.ifEmpty { it.link } } else candidates).take(maxCandidates)
        if (ordered.isEmpty() || want <= 0) return emptyList()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val found = ConcurrentLinkedQueue<Found>()
        val done = CompletableDeferred<Unit>()
        val remaining = AtomicInteger(ordered.size)
        val foundN = AtomicInteger()
        val probedN = AtomicInteger()
        val stop = AtomicBoolean(false)
        suspectedOffline = false
        aborted = false
        val tcpOk = AtomicInteger()
        val tcpFail = AtomicInteger()
        val failedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // с замером шлюза начинаем с малого числа потоков и наращиваем; без замера — сразу на полную
        val tcpGate = AdaptiveGate(if (loadSample != null) AdaptiveGate.SLOW_START else tcpParallel, tcpParallel)
        val monitor = loadSample?.let { sample ->
            scope.launch {
                val load = GatewayLoad()
                while (!stop.get()) {
                    kotlinx.coroutines.delay(1_000)
                    val rtt = try { sample() } catch (_: Throwable) { -1L }
                    if (load.disabled) break
                    if (load.feed(rtt) == GatewayLoad.Verdict.BUSY) tcpGate.busy() else tcpGate.ok()
                    if (load.disabled) break
                }
            }
        }
        val probeGate = Semaphore(probeParallel)
        val hostBad = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val hostGood = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val skippedN = AtomicInteger()
        val hostKeyOf = { c: Candidate -> ServerFingerprint.transportKey(c.link) }
        val tcpMsSum = java.util.concurrent.atomic.AtomicLong()
        val probeMsSum = java.util.concurrent.atomic.AtomicLong()
        val probeOkMsSum = java.util.concurrent.atomic.AtomicLong()
        val probeOkN = AtomicInteger()

        for (c in ordered) {
            scope.launch {
                try {
                    if (stop.get()) return@launch
                    val parsed = LinkParser.parse(c.link)
                    var hosts: Map<String, String> = emptyMap()
                    if (parsed?.host != null && parsed.port != null) {
                        val ip = tcpGate.run {
                            if (stop.get()) return@run null
                            val t0 = System.nanoTime()
                            try {
                                if (parsed.protocol == "hysteria2") ServerHosts.resolveOnly(parsed.host) else reach(parsed.host, parsed.port)
                            } finally {
                                tcpMsSum.addAndGet((System.nanoTime() - t0) / 1_000_000)
                            }
                        }
                        if (stop.get() && ip == null && !suspectedOffline) return@launch
                        if (ip == null) {
                            onProbe(c, TCP_FAIL)
                            failedHosts.add("${parsed.host}:${parsed.port}")
                            val failN = tcpFail.incrementAndGet() // считаем всегда: раньше после первого открытого порта провалы не учитывались («не открылись 0» при ~1500 отброшенных, журнал 20.11)
                            if (tcpOk.get() == 0 && failN >= offlineSuspect && failedHosts.size >= minOf(OFFLINE_MIN_HOSTS, offlineSuspect)) {
                                suspectedOffline = true
                                stop.set(true)
                                done.complete(Unit)
                            }
                            return@launch
                        }
                        tcpOk.incrementAndGet()
                        hosts = ServerHosts.hostsFor(parsed.host, ip)
                    }
                    val config = try {
                        XrayConfig.probeConfig(c.link, hosts)
                    } catch (_: UnsupportedLinkException) {
                        onProbe(c, CheckServices.UNSUPPORTED)
                        return@launch
                    }
                    val hk = if (hostStrikes > 0) hostKeyOf(c) else ""
                    val skipHost = { hk.isNotEmpty() && hk !in hostGood && (hostBad[hk]?.get() ?: 0) >= hostStrikes }
                    if (skipHost()) {
                        skippedN.incrementAndGet()
                        onProbe(c, HOST_SKIPPED)
                        return@launch
                    }
                    val ms = probeGate.withPermit {
                        if (stop.get()) return@launch
                        if (skipHost()) return@withPermit HOST_SKIPPED
                        probedN.incrementAndGet()
                        val t0 = System.nanoTime()
                        val r = try { probe(config, url) } finally { probeMsSum.addAndGet((System.nanoTime() - t0) / 1_000_000) }
                        if (r >= 0) { probeOkN.incrementAndGet(); probeOkMsSum.addAndGet((System.nanoTime() - t0) / 1_000_000) }
                        // итог по адресу записываем, пока держим место: следующая проба этого же адреса уже увидит его
                        if (hk.isNotEmpty()) {
                            if (r >= 0) hostGood.add(hk) else hostBad.getOrPut(hk) { AtomicInteger() }.incrementAndGet()
                        }
                        r
                    }
                    if (ms == HOST_SKIPPED) skippedN.incrementAndGet()
                    onProbe(c, ms)
                    if (ms >= 0) {
                        found += Found(c, ms)
                        if (foundN.incrementAndGet() >= want) {
                            stop.set(true)
                            done.complete(Unit)
                        }
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) done.complete(Unit)
                }
            }
        }
        try {
            withTimeoutOrNull(budgetMs) {
                while (!done.isCompleted) {
                    if (shouldStop?.invoke() == true) { aborted = true; break }
                    withTimeoutOrNull(500) { done.await() }
                }
            }
        } finally {
            // и при отмене снаружи (система остановила задачу) проверки не должны продолжать работать в фоне
            stop.set(true)
            throttled = tcpGate.throttled
            monitor?.cancel()
            scope.cancel()
        }
        triedTcp = tcpOk.get() + tcpFail.get()
        tcpFailed = tcpFail.get()
        probed = probedN.get()
        hostSkipped = skippedN.get()
        tcpMsTotal = tcpMsSum.get()
        probeMsTotal = probeMsSum.get()
        probeOkMsTotal = probeOkMsSum.get()
        probeOkCount = probeOkN.get()
        return found.sortedBy { it.ms }.take(want)
    }
}
