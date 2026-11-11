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
) {
    companion object {
        /** Сервер не открыл TCP-соединение (в этой сети он недоступен; в другой может быть жив). */
        const val TCP_FAIL = -3L

        /** Столько серверов подряд без единого открытого TCP — почти наверняка нет интернета, а не умерли все серверы. */
        const val OFFLINE_SUSPECT = 120

        /** …и не меньше стольких разных host:port (несколько конфигов одного сервера не должны давать «нет интернета»). */
        const val OFFLINE_MIN_HOSTS = 40
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

        for (c in ordered) {
            scope.launch {
                try {
                    if (stop.get()) return@launch
                    val parsed = LinkParser.parse(c.link)
                    var hosts: Map<String, String> = emptyMap()
                    if (parsed?.host != null && parsed.port != null) {
                        val ip = tcpGate.run {
                            if (stop.get()) return@run null
                            if (parsed.protocol == "hysteria2") ServerHosts.resolveOnly(parsed.host) else reach(parsed.host, parsed.port)
                        }
                        if (stop.get() && ip == null && !suspectedOffline) return@launch
                        if (ip == null) {
                            onProbe(c, TCP_FAIL)
                            failedHosts.add("${parsed.host}:${parsed.port}")
                            if (tcpOk.get() == 0 && tcpFail.incrementAndGet() >= OFFLINE_SUSPECT && failedHosts.size >= OFFLINE_MIN_HOSTS) {
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
                    val ms = probeGate.withPermit {
                        if (stop.get()) return@launch
                        probedN.incrementAndGet()
                        probe(config, url)
                    }
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
        return found.sortedBy { it.ms }.take(want)
    }
}
