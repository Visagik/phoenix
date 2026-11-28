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
    internal val voteLock = Any()

    private val muteDetect = RepeatMute()

    /** Когда прямая проверка в последний раз показала «нет сети» (0 — после этого сеть была). Нужна, чтобы не перебирать серверы без интернета. */
    private val offlineFact = EpochFact(0L)
    /** Действует только в эпохе сети, где замер сделан (см. [NetEpoch]). */
    var offlineAt: Long
        get() = offlineFact.value
        set(v) { offlineFact.value = v }

    /** Последний результат определения и когда он получен (для отсрочки перебора, пока смена режима не подтвердилась). */
    private val lastTypeFact = EpochFact<BlockType?>(null)
    private val lastTypeAtFact = EpochFact(0L)
    var lastType: BlockType?
        get() = lastTypeFact.value
        set(v) { lastTypeFact.value = v }
    var lastTypeAt: Long
        get() = lastTypeAtFact.value
        set(v) { lastTypeAtFact.value = v }

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

    @Volatile private var mismatchSince = 0L

    /** Замер шёл по мобильной сети (физическая сеть, выбранная для замера, — сотовая). */
    private fun measuredOnCellular(context: Context): Boolean = try {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val n = physicalNetwork(context)
        n != null && cm?.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    } catch (_: Throwable) {
        false
    }

    /** Один прямой замер на всех (Р1): определение режима и замер заранее при сбое связи не запускают два одинаковых замера разом. */
    private val shared = SharedMeasure<Measured>()

    /** Цепочка причин исключения («SSLHandshakeException ← CertPathValidatorException»): по ней видно, не доверяем ли мы сертификату (Д1). */
    internal fun causeChain(e: Throwable): String {
        val names = ArrayList<String>()
        var c: Throwable? = e.cause
        while (c != null && names.size < 3) { names += c.javaClass.simpleName; c = c.cause?.takeIf { it !== c } }
        return names.joinToString(" ← ")
    }

    internal fun whyOf(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "DNS"
        is java.net.SocketTimeoutException -> "таймаут"
        is java.net.ConnectException -> "отказ"
        is javax.net.ssl.SSLException -> causeChain(e).let { if (it.isEmpty()) "TLS" else "TLS: $it" }
        else -> "другое"
    }


    private fun reachable(url: String, net: android.net.Network?, why: MutableMap<String, String>): Boolean = try {
        val c = (net?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        c.connectTimeout = 4_000
        c.readTimeout = 4_000
        c.instanceFollowRedirects = false
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Phoenix)")
        val code = c.responseCode
        c.disconnect()
        code in 200..399 || code in 400..499 // любой ответ сервера значит, что сайт доступен (блокировка обрывает соединение)
    } catch (e: Exception) {
        if (TlsOutcome.answeredDespite(url, e)) {
            // российский сайт ответил, но его сертификат Android не принимает: для замера он открыт. В why не пишем:
            // там только не открывшиеся адреса (по ней строятся «не открылись» и сводка причин)
            true
        } else {
            why[url] = whyOf(e) // почему адрес не открылся: отличает сбой DNS роутера от таймаута и от отказа
            false
        }
    }

    fun measure(context: Context? = null): ProbeCounts = measureOnce(context).counts

    private fun measureOnce(context: Context?): Measured {
        val urls = BlockDetector.FOREIGN + BlockDetector.RUSSIAN
        val why = java.util.concurrent.ConcurrentHashMap<String, String>()
        val epoch = NetEpoch.id
        val net = context?.let { physicalNetwork(it) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(urls.map { u -> Callable { u to reachable(u, net, why) } }, 14, TimeUnit.SECONDS)
                .mapNotNull { f -> try { if (f.isCancelled) null else f.get() } catch (_: Exception) { null } }
                .toMap()
            val opened = results.filterValues { it }.keys
            urls.filter { it !in opened }.forEach { why.putIfAbsent(it, "не успели") }
            return Measured(
                ProbeCounts(
                    foreignOk = BlockDetector.FOREIGN.count { it in opened },
                    foreignTotal = BlockDetector.FOREIGN.size,
                    ruOk = BlockDetector.RUSSIAN.count { it in opened },
                    ruTotal = BlockDetector.RUSSIAN.size,
                ),
                why.toMap(), System.currentTimeMillis(), epoch,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Замер с проверкой на сбой: если российские не открылись ни один, а сеть у телефона есть, через 4 секунды замеряем ещё раз
     * и берём лучший. Настоящее «нет сети» этим только дольше на несколько секунд, а ложное («нет сети» при рабочем Wi-Fi) не проходит
     * и не блокирует смену сервера (offlineAt).
     */
    /**
     * Когда прямой замер в последний раз ничего не открыл при живой сети («Wi-Fi дёрнулся»). Даже если повтор через 4 секунды прошёл,
     * сбои проверки связи в это время — вина сети, а не сервера: проверка связи на эту минуту не винит сервер (как после смены сети).
     */
    val wobbleAt: Long get() = NetState.wobbleAt

    /**
     * Время разрешения имён по физической сети. DNS не входит в connectTimeout замера: если DNS роутера завис, все потоки замера
     * ждут по 5–10 с и к 14 с «не успели» (счёт 0 из 15). Эта проверка отличает такой сбой от сбоя канала.
     */
    private fun dnsProbe(context: Context): String {
        val net = physicalNetwork(context)
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val futures = listOf("ya.ru", "www.gstatic.com").map { h ->
                pool.submit(Callable {
                    val t = System.currentTimeMillis()
                    val ok = try { (net?.getAllByName(h) ?: java.net.InetAddress.getAllByName(h)).isNotEmpty() } catch (_: Exception) { false }
                    "$h ${if (ok) "" else "ошибка "}${System.currentTimeMillis() - t} мс"
                })
            }
            futures.joinToString(", ") { f -> try { f.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "не ответил за 5 с" } }
        } finally {
            pool.shutdownNow()
        }
    }

    fun measureConfirmed(context: Context): ProbeCounts = measuredConfirmed(context).counts

    /** Замер с повтором при подозрении, общий для всех одновременных вызывающих (Р1); причины принадлежат самому результату. */
    fun measuredConfirmed(context: Context): Measured = shared.get(NetEpoch.id) { measureConfirmedOwn(context) }

    private fun measureConfirmedOwn(context: Context): Measured {
        val firstM = measureOnce(context)
        val first = firstM.counts
        if (!BlockDetector.suspect(first) || NetInfo.kind(context) == NetInfo.Kind.NONE) return firstM
        NetState.wobble(System.currentTimeMillis())
        val why1 = firstM.whySummary()
        try { Thread.sleep(4_000) } catch (_: InterruptedException) { return firstM }
        val secondM = measureOnce(context)
        val second = secondM.counts
        // всегда пишем, чем кончился повтор и почему адреса не открывались: «DNS» — сбой DNS роутера, «таймаут» — канал, «не успели» — перегрузка
        val text = "первый замер подозрительный (${first.foreignOk} из ${first.foreignTotal}, ${first.ruOk} из ${first.ruTotal}; $why1) — повтор: ${second.foreignOk} из ${second.foreignTotal}, ${second.ruOk} из ${second.ruTotal}" +
            (secondM.whySummary().takeIf { it.isNotEmpty() }?.let { "; $it" } ?: "") + "; DNS: ${dnsProbe(context)}"
        if (muteDetect.shouldLog("detect-retry", text, System.currentTimeMillis())) Storage(context).log("DETECT: $text")
        return if (BlockDetector.better(first, second) === first) firstM else secondM
    }

    private fun staleRun(storage: Storage, counts: ProbeCounts): Outcome {
        val text = "замер устарел (запущен новый) — не засчитываю"
        storage.log("DETECT: $text")
        return Outcome(BlockType.UNKNOWN, counts, null, text)
    }

    /** Одно определение; режим меняется только после нескольких одинаковых подряд (и если включено автоопределение). */
    /** Номер последнего запущенного определения (Д2): отменённый замер не прерывается и не должен записывать результат. */
    private val runSeq = java.util.concurrent.atomic.AtomicLong()

    fun run(context: Context, need: Int = ModeDecider.NEED_IN_ROW, ignoreHold: Boolean = false): Outcome {
        // Lite: режим всегда «Чёрные списки», определение не запускается (нет замеров и строк DETECT)
        if (LiteProfile.ON) return Outcome(BlockType.UNKNOWN, ProbeCounts(0, 0, 0, 0), null, "Lite: режим фиксирован")
        val my = runSeq.incrementAndGet()
        val settings = AppSettings(context)
        val storage = Storage(context)
        val netStart = NetInfo.place(context)
        val measured = measuredConfirmed(context)
        val counts = measured.counts
        val type = BlockDetector.classify(counts)
        if (!DetectSeq.isLatest(my, runSeq.get())) return staleRun(storage, counts)
        // сеть сменилась, пока шёл замер (до 14 с): часть адресов открывалась по одной сети, часть по другой — результат отбрасываем,
        // голосов, «нет сети» и памяти режима по нему не делаем; новая сеть запустит своё определение
        val netEnd = NetInfo.place(context)
        // по какой сети шёл замер: Wi-Fi без интернета при живой мобильной — замер по мобильной, а подпись «wifi»
        val onCellular = measuredOnCellular(context)
        val nominalWifi = NetInfo.kind(context) == NetInfo.Kind.WIFI
        val nowMs = System.currentTimeMillis()
        if (onCellular && nominalWifi) { if (mismatchSince == 0L) mismatchSince = nowMs } else mismatchSince = 0L
        if (NetMismatch.ignore(onCellular, nominalWifi, mismatchSince, nowMs)) {
            val text = "Wi-Fi без интернета, замер шёл по мобильной сети — режим не меняю и сеть не запоминаю"
            if (muteDetect.shouldLog("detect-mismatch", text, nowMs)) storage.log("DETECT: $text (зарубежных ${counts.foreignOk}/${counts.foreignTotal}, российских ${counts.ruOk}/${counts.ruTotal})")
            return Outcome(BlockType.UNKNOWN, counts, null, text)
        }
        // затянувшееся расхождение — это результат мобильной сети, запоминаем его под её именем, а не под Wi-Fi
        val noteLabel = if (onCellular && nominalWifi) NetLabel.of("MOBILE", try { context.getSystemService(android.telephony.TelephonyManager::class.java)?.networkOperatorName } catch (_: Throwable) { null }) else netEnd
        if (ModeHint.stale(netStart, netEnd)) {
            val text = "сеть сменилась во время замера ($netStart → $netEnd) — результат отбрасываю"
            if (muteDetect.shouldLog("detect-stale", text, System.currentTimeMillis())) storage.log("DETECT: $text")
            return Outcome(BlockType.UNKNOWN, counts, null, text)
        }
        if (WobbleGuard.ignore(type, storage.mode, nominalWifi, NetState.isShaky(nowMs))) {
            val text = "Wi-Fi только что дёргался (система предупредила о потере сети или сеть прыгала) — «белые списки» по этому замеру не засчитываю"
            if (muteDetect.shouldLog("detect-wobble", text, nowMs)) storage.log("DETECT: $text (зарубежных ${counts.foreignOk}/${counts.foreignTotal}, российских ${counts.ruOk}/${counts.ruTotal})")
            return Outcome(BlockType.UNKNOWN, counts, null, text)
        }
        offlineAt = if (type == BlockType.OFFLINE) System.currentTimeMillis() else 0L
        lastType = type
        lastTypeAt = System.currentTimeMillis()
        if (ModeDecider.modeOf(type) != null) ModeGate.knownNet = noteLabel

        var switched: Mode? = null
        val held = !ignoreHold && ModeHold.active(settings.manualModeAt, settings.manualModeNet, System.currentTimeMillis(), NetInfo.label(context))
        if (ignoreHold) settings.manualModeAt = 0L
        val needNow = ModeDecider.needVotes(type, storage.mode, ModeHint.load(storage.modeHintFile), noteLabel, NetState.isFlapping(nowMs), held, need)
        if (settings.autoMode) {
            // чтение–изменение–запись голосов под замком: определение запускают несколько мест сразу (смена сети,
            // проверка связи, кнопка), и два одновременных замера иначе засчитывались как два «подряд» (находка 3.3)
            // проверка «я последний» — под тем же замком, что и запись голоса: между ними новый запуск не вклинится (Д2)
            val voted = synchronized(voteLock) {
                if (!DetectSeq.isLatest(my, runSeq.get())) null else {
                    val r = ModeDecider.step(ModeDecider.load(storage.voteFile), type, storage.mode, needNow)
                    ModeDecider.save(storage.voteFile, r.first)
                    r
                }
            } ?: return staleRun(storage, counts)
            val (vote, target) = voted
            // при ручном выборе голоса копятся, но режим меняется, только если определение упорно (4 подряд) спорит с выбором
            if (target != null && (!held || ModeHold.overridden(vote.count))) {
                if (held) settings.manualModeAt = 0L
                Mode.write(context.applicationContext.filesDir, target)
                switched = target
            }
        }
        // режим, подтверждённый в этой сети, запоминаем: при следующей смене сети он станет подсказкой
        ModeDecider.modeOf(type)?.let { m ->
            synchronized(voteLock) {
                if (DetectSeq.isLatest(my, runSeq.get())) ModeHint.save(storage.modeHintFile, ModeHint.note(ModeHint.load(storage.modeHintFile), noteLabel, m))
            }
        }
        val text = "%s · зарубежных открылось %d из %d, российских %d из %d (сеть %s)".format(
            BlockDetector.title(type), counts.foreignOk, counts.foreignTotal, counts.ruOk, counts.ruTotal, NetLabel.title(NetInfo.label(context)),
        ) + (switched?.let { " → режим «${it.title}»" + (if (needNow < need) " (обычный для сети $noteLabel — хватило одного замера)" else "") } ?: "") +
            (if (held && settings.autoMode && ModeDecider.modeOf(type).let { it != null && it != storage.mode }) " (режим выбран вручную — не меняю)" else "")
        settings.lastDetect = text
        settings.lastDetectAt = System.currentTimeMillis()
        // та же строка подряд — не чаще раза в 30 минут (смена режима или сети меняет текст и пишется сразу)
        // какие российские адреса не открылись и почему (в строке на экране этого нет): для разбора «российских 3 из 4» (Д1)
        val ruFail = measured.failedWithWhy(BlockDetector.RUSSIAN).takeIf { it.isNotEmpty() && counts.ruOk < counts.ruTotal }?.let { "; не открылись: $it" } ?: ""
        if (muteDetect.shouldLog("detect", text, System.currentTimeMillis())) Storage(context).log("DETECT: $text$ruFail")
        return Outcome(type, counts, switched, text)
    }
}
