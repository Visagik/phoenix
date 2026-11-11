package com.phoenix.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime

/** Состояние текущего прогона для UI. */
object RunState {
    val running = MutableStateFlow(false)
    val message = MutableStateFlow("")
}

/**
 * Полный конвейер Phoenix: источники (общие + свои) -> blacklist -> загрузка -> аудит пересечений
 * -> дедупликация -> топ-N на адрес -> GeoIP-обогащение -> трекер стабильности -> отчёт.
 * Заменяет save_vpn_configs.sh + geoip_enrich.sh + track_stable_configs.sh + dedup_*.sh.
 */
class Collector(
    context: Context,
    private val sourcesListUrl: String? = null,
    /** Для тестов: подменяет «сегодня», чтобы проверить автоприменение аудита 1-го и 15-го. */
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    /** Для какого режима собирать; по умолчанию — текущий. У каждого режима свои источники и данные. */
    mode: Mode? = null,
) {
    private val appContext = context.applicationContext
    private val storage = if (mode != null) Storage(context.applicationContext, mode) else Storage(context.applicationContext)

    data class Outcome(val ok: Boolean, val summary: String)

    private data class Fetched(val code: Int, val body: String?, val vpnTried: Boolean = false, val note: String = "", val etag: String = "", val lastMod: String = "")
    private class Pending(val url: String, val start: SourceState, val reactivated: Boolean)

    /** @param trigger кто запустил (alarm / worker / manual / catch-up) — попадает в журнал для диагностики */
    suspend fun run(trigger: String = "manual"): Outcome {
        if (!lock.tryLock()) return Outcome(true, "Сборка уже выполняется")
        RunState.running.value = true
        return try {
            withContext(Dispatchers.IO) { pipeline(trigger) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storage.log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            progress("Ошибка: ${e.message}")
            Outcome(false, "Ошибка: ${e.message}")
        } finally {
            RunState.running.value = false
            lock.unlock()
        }
    }

    private fun progress(text: String) {
        RunState.message.value = text
    }

    private fun writeReport(draft: ReportDraft, started: Long, today: String) {
        draft.durationMs = System.currentTimeMillis() - started
        File(storage.dateDir(today), Storage.REPORT_NAME).writeTextAtomic(LogMask.apply(ReportBuilder.render(draft)))
    }

    private suspend fun pipeline(trigger: String): Outcome {
        val started = System.currentTimeMillis()
        val todayDate = todayProvider()
        val today = todayDate.toString()
        val draft = ReportDraft(OffsetDateTime.now())
        draft.notes += "режим: ${storage.mode.title}"
        draft.notes += "запуск: $trigger"
        storage.log("START: сборка [$trigger]")

        fun fail(reason: String): Outcome {
            draft.failure = reason
            writeReport(draft, started, today)
            storage.log("FAILED: $reason")
            progress(reason)
            return Outcome(false, reason)
        }

        // --- 1. Список источников: общий (с запасным кэшем) + свои ссылки ---
        progress("Загружаю список источников…")
        val listUrl = sourcesListUrl ?: storage.sourcesListUrl()
        val remote = if (listUrl.isBlank()) null else fetchWithRetry(listUrl, 2).body
        val listText: String? = when {
            listUrl.isBlank() -> null
            remote != null -> remote.also { storage.sourcesCacheFile.writeTextAtomic(it) }
            storage.sourcesCacheFile.exists() -> {
                draft.notes += "общий список источников недоступен — использован сохранённый"
                storage.sourcesCacheFile.readText()
            }
            else -> null
        }
        val mine = UserSources(storage.userSourcesFile).load()
        draft.mySourcesCount = mine.size
        val allUrls = (listText?.let { UserSources.parseSourceList(it) }.orEmpty() + DefaultSources.forMode(storage.mode) + mine).distinct()
        if (allUrls.isEmpty()) {
            return fail(
                if (listUrl.isBlank()) "Нет источников: добавьте свои ссылки или адрес общего списка (вкладка «Источники»)"
                else "Нет сети или список источников недоступен"
            )
        }

        val blacklist = Blacklist(storage.blacklistFile)
        if (!storage.blacklistFile.exists() && storage.mode == Mode.WHITELIST) {
            DEFAULT_BLACKLIST.forEach { blacklist.add(it, "по умолчанию (из Termux-версии)") }
        }
        val blocked = blacklist.urls()
        draft.blacklistSize = blocked.size
        val urls = allUrls.filter { it !in blocked && !DefaultSources.isExcluded(it, mine) }
        storage.log("PARSE: источников ${allUrls.size} (своих ${mine.size}), после blacklist: ${urls.size}")

        // --- 2. Состояние источников ---
        val states = LinkedHashMap<String, SourceState>()
        if (storage.stateFile.exists()) {
            storage.stateFile.forEachLine { line -> SourceState.parse(line)?.let { states[it.first] = it.second } }
        }

        val toFetch = ArrayList<Pending>()
        for (u in urls) {
            val st = states[u] ?: SourceState()
            if (st.active) {
                toFetch += Pending(u, st, reactivated = false)
            } else if (SourcePolicy.shouldReactivate(st, todayDate)) {
                toFetch += Pending(u, st.copy(active = true, fails = 0, react = st.react + 1), reactivated = true)
            } else if (st.react >= SourcePolicy.MAX_REACTIVATIONS) {
                draft.sources += SourceLine(u, SourceKind.PERMANENT)
            } else {
                draft.sources += SourceLine(u, SourceKind.BLOCKED, "до повторной проверки")
            }
        }

        // --- 3. Параллельная загрузка ---
        progress("Скачиваю источники (${toFetch.size})…")
        val deadline = started + MAX_FETCH_MS
        // число одновременных загрузок подчиняется общему ограничению нагрузки на роутер (как у проверок серверов): в плохой сети меньше
        val level = try { NetHooks.level(storage, NetHooks.label()) } catch (_: Throwable) { 0 }
        val parallel = (PARALLEL shr level).coerceIn(2, PARALLEL).let { if (try { NetHooks.mobile() } catch (_: Throwable) { false }) minOf(it, 3) else it }
        val semaphore = Semaphore(parallel)
        val schedule = SourceSchedule(storage.sourceScheduleFile)
        val validators = SourceValidators(storage.sourceValidatorsFile)
        val manual = trigger == "manual"
        storage.sourceCacheDir.mkdirs()
        val fromCacheN = java.util.concurrent.atomic.AtomicInteger()
        val notModifiedN = java.util.concurrent.atomic.AtomicInteger()
        val viaVpnN = java.util.concurrent.atomic.AtomicInteger()
        val results: List<Fetched?> = coroutineScope {
            toFetch.map { p ->
                async {
                    semaphore.withPermit {
                        val cacheFile = File(storage.sourceCacheDir, SourceCadence.hashOf(setOf(p.url)) + ".txt")
                        val cachedAny = if (cacheFile.exists()) try { cacheFile.readText() } catch (_: Exception) { null } else null
                        val cached = if (!manual) cachedAny else null
                        if (System.currentTimeMillis() > deadline) null
                        else if (!manual && schedule.skip(p.url, System.currentTimeMillis(), cached != null)) {
                            schedule.noteSkip(p.url)
                            fromCacheN.incrementAndGet()
                            Fetched(200, cached, note = "из кэша: по накопленной истории источник ещё не обновлялся")
                        } else fetchWithRetry(p.url, RETRIES, SourceValidators.requestHeaders(validators.get(p.url), cachedAny != null)).let { f ->
                            // 304: сервер подтвердил, что источник не менялся — берём сохранённый ответ без скачивания
                            if (f.code == 304 && cachedAny != null) { notModifiedN.incrementAndGet(); Fetched(200, cachedAny, note = "из кэша: источник не изменился (304)") } else f
                        }.also { f ->
                            if (f.code == 200 && f.body != null && !f.note.startsWith("из кэша")) validators.put(p.url, f.etag, f.lastMod)
                            if (f.body != null && f.note == "через VPN") viaVpnN.incrementAndGet()
                            if (f.body != null && FetchPolicy.looksLikeSubscription(f.body)) try { cacheFile.writeTextAtomic(f.body) } catch (_: Exception) { }
                        }
                    }
                }
            }.awaitAll()
        }
        val fromCache = fromCacheN.get()
        val notModified = notModifiedN.get()
        val viaVpn = viaVpnN.get()
        if (parallel < PARALLEL) storage.log("FETCH: параллельных загрузок $parallel из $PARALLEL (ограничение нагрузки на сеть)")

        val okSources = ArrayList<SourceSet>()
        val bodies = ArrayList<String>()
        // какие конфиги пришли из какого источника — для доли рабочих по каждой ссылке (старые записи остаются, пока источник не ответит заново)
        val members = LinkedHashMap<String, Set<String>>()
        if (storage.sourceMembersFile.exists()) {
            try {
                members += SourceStats.decode(storage.sourceMembersFile.readText())
            } catch (_: Exception) {
            }
        }
        toFetch.zip(results).forEach { (p, res) ->
            if (res == null) {
                draft.sources += SourceLine(p.url, SourceKind.SKIPPED, reactivated = p.reactivated)
                return@forEach
            }
            val body = res.body
            if (body != null) {
                bodies += body
                val bodyLines = expandBody(body)
                val keys = bodyLines.mapNotNull { LinkParser.parse(it)?.urlPart }.toSet()
                val newMembers = bodyLines.filter { it.contains("://") }.map { CheckStore.keyOf(it.trim()) }.toSet()
                SourceDrift.check(members[p.url], newMembers)?.let { storage.log("DRIFT: ${p.url} — ${it.text}") }
                if (!res.note.startsWith("из кэша")) {
                    val changed = schedule.record(p.url, SourceCadence.hashOf(newMembers), System.currentTimeMillis())
                    schedule.intervalHours(p.url)?.let { h -> if (changed) storage.log("SCHED: ${p.url} изменился, обычный интервал обновления ~${"%.1f".format(java.util.Locale.US, h)} ч") }
                }
                members[p.url] = bodyLines.filter { it.contains("://") }.map { CheckStore.keyOf(it.trim()) }.toSet()
                okSources += SourceSet(p.url, okSources.size, keys)
                states[p.url] = SourcePolicy.onSuccess(today)
                storage.log("OK: ${p.url} (${body.length} симв., ${keys.size} конфигов)" + (if (res.note.isNotEmpty()) " [${res.note}]" else "") + (if (keys.isEmpty()) " — в ответе нет конфигов (страница-заглушка или пустой источник)" else ""))
                draft.sources += SourceLine(p.url, SourceKind.OK, "${body.length} симв., ${keys.size} конфигов", p.reactivated)
            } else {
                val ns = SourcePolicy.onFailure(p.start, res.code, today)
                // ошибка, по которой нельзя судить, что источник умер (блокировка, защита от ботов, обрыв), без проверки через VPN в счёт не идёт
                val uncertain = FetchPolicy.uncertain(res.code) && !res.vpnTried
                states[p.url] = if (uncertain) p.start else ns
                storage.log("FAIL: ${p.url} (код ${res.code}, " + (if (uncertain) "в счёт неудач не идёт" else "неудача #${ns.fails}") + (if (res.note.isNotEmpty()) "; ${res.note}" else "") + ")")
                val detail = "код ${res.code}, неудача #${ns.fails}" + (if (res.note.isNotEmpty()) " (${res.note})" else "") + if (!ns.active) ", заблокирован" else ""
                draft.sources += SourceLine(p.url, SourceKind.FAIL, detail, p.reactivated)
            }
        }

        try { schedule.forget(urls.toSet()); schedule.save() } catch (_: Throwable) { }
        // удалённый источник не должен оставлять в кэше своё содержимое (в нём могут быть персональные конфиги); кэш заблокированных не трогаем — их могут вернуть
        try {
            val keepNames = allUrls.map { SourceCadence.hashOf(setOf(it)) + ".txt" }.toHashSet()
            val old = storage.sourceCacheDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") && f.name !in keepNames }.orEmpty()
            var removed = 0
            for (f in old) if (f.delete()) removed++
            val dropped = validators.retainOnly(allUrls)
            if (removed > 0 || dropped > 0) storage.log("FETCH: удалено старых кэшей $removed, меток обновления $dropped")
        } catch (_: Throwable) { }
        try { validators.save() } catch (_: Throwable) { }
        if (notModified > 0) storage.log("FETCH: источников не менялось (ответ 304, без скачивания): $notModified")
        // учёт нагрузки по типам: скачивания источников (кэш и ответы 304 не считаются)
        try { EnergyMeter.of(java.io.File(appContext.filesDir, "energy.tsv")).add("fetch", (toFetch.size - fromCache - notModified).coerceAtLeast(0), java.time.LocalDate.now().toString(), System.currentTimeMillis()) } catch (_: Throwable) { }
        if (fromCache > 0 || viaVpn > 0) storage.log("FETCH: из кэша $fromCache, через VPN $viaVpn, из ${toFetch.size}")
        // ни один источник не ответил — скорее всего нет сети у телефона, а не умерли все ссылки: счётчики неудач не трогаем,
        // иначе несколько сборов в плохой сети подряд заблокировали бы все источники на неделю
        if (bodies.isEmpty()) return fail("Ни один источник не ответил — вчерашний файл сохранён")

        storage.stateFile.writeTextAtomic(states.entries.joinToString("\n") { it.value.toLine(it.key) } + "\n")
        try {
            // в файле только источники, которые сейчас в списке (убранные и в чёрном списке не нужны)
            val current = urls.toSet()
            storage.sourceMembersFile.writeTextAtomic(SourceStats.encode(members.filterKeys { it in current }))
        } catch (_: Exception) {
        }

        // --- 4. Аудит пересечений между источниками ---
        progress("Аудит источников…")
        val keep = KeepList(storage.keepFile).load() +
            SourcePriorities(storage.sourcePriorityFile).load().filterValues { it >= SourcePriority.HIGH }.keys
        val audit = SourceAudit.analyze(okSources).let { r ->
            AuditResult(r.proposals.filter { it.url !in keep }, r.overlaps)
        }
        val applyDay = todayDate.dayOfMonth == 1 || todayDate.dayOfMonth == 15
        var applied = emptyList<Proposal>()
        if (applyDay) {
            applied = SourceAudit.select(audit.proposals, blacklist.load(), SourceAudit.MAX_APPLY)
            if (applied.isNotEmpty()) {
                try { UndoStore(storage.undoDir).snapshot("Аудит источников: в чёрный список добавлено ${applied.size}", listOf(storage.blacklistFile), System.currentTimeMillis()) } catch (_: Throwable) {}
            }
            applied.forEach { blacklist.add(it.url, it.reason) }
            if (applied.isNotEmpty()) storage.log("AUDIT: в чёрный список добавлено ${applied.size}")
        }
        val nowBlocked = blacklist.urls()
        val pending = audit.proposals.filter { it.url !in nowBlocked }
        storage.proposalsFile.writeTextAtomic(SourceAudit.serialize(pending))
        draft.blacklistSize = nowBlocked.size
        draft.audit = AuditSummary(audit.overlaps, applied, pending.size, applyDay)
        storage.log("AUDIT: пересечений ${audit.overlaps.size}, предложений ${audit.proposals.size}, ждут ${pending.size}")

        // --- 5. Дедупликация ---
        progress("Дедупликация…")
        val lines = bodies.flatMap { expandBody(it) }
        val cleaned = Dedup.clean(lines)
        val top = Dedup.topPerAddress(cleaned, MAX_PER_ADDRESS)
        draft.rawLines = lines.size
        draft.afterClean = cleaned.size
        draft.afterTop = top.size
        draft.protocols = top.groupingBy { it.protocol }.eachCount()
        storage.log("DEDUP: строк ${lines.size}, после очистки ${cleaned.size}, после топ-$MAX_PER_ADDRESS ${top.size}")
        try {
            val rep = ServerFingerprint.likelyRepeats(top.map { it.urlPart })
            if (rep.isNotEmpty()) storage.log("DEDUP: вероятных повторов по отпечатку (те же учётные данные и ключи, другой адрес): ${rep.size} групп, ${rep.sumOf { it.size }} конфигов — не удаляю")
        } catch (_: Throwable) {
        }

        val okCount = draft.sources.count { it.kind == SourceKind.OK }
        val failCount = draft.sources.count { it.kind == SourceKind.FAIL }
        val dir = storage.dateDir(today)
        val header = buildString {
            appendLine("# Объединённые VPN-конфиги за $today")
            appendLine("# Сгенерировано: ${OffsetDateTime.now()}")
            appendLine("# Источников: ${urls.size} (успех: $okCount, неудача: $failCount)")
            appendLine("# Уникальных конфигов: ${top.size}")
            appendLine()
        }
        File(dir, Storage.CLEAN_NAME).writeTextAtomic(
            header + top.joinToString("\n") { it.urlPart + if (it.name.isNotEmpty()) "#" + it.name else "" } + "\n"
        )

        // --- 6. GeoIP-обогащение ---
        progress("Определяю страны…")
        val (enrichedLines, geo) = Enricher(storage).enrich(top)
        File(dir, Storage.ENRICHED_NAME).writeTextAtomic(header + enrichedLines.joinToString("\n") + "\n")
        draft.geo = geo
        storage.log(
            "ENRICH: обогащено ${geo.enriched}, без хоста ${geo.noHost}, страна неизвестна ${geo.unknownCountry}, " +
                "из имён ${geo.fromNames}, по таблице IP ${geo.apiAttempts}"
        )

        // --- 7. Трекер стабильности ---
        draft.trackerSummary = Tracker(storage).update(top, today)
        storage.log("TRACK: ${draft.trackerSummary}")

        // --- 8. Отчёт (отдельный файл report.txt) ---
        writeReport(draft, started, today)
        storage.cleanupOld()

        val summary = "Готово: источников $okCount/${urls.size}, конфигов ${top.size}, с метками ${geo.enriched}"
        storage.log("DONE: $summary")
        progress(summary)
        return Outcome(true, summary)
    }

    /** Подписка может быть целиком в base64 — тогда раскодируем. */
    private fun expandBody(body: String): List<String> {
        val text = if (body.contains("://")) body else (LinkParser.decodeBase64(body) ?: body)
        return text.lines()
    }

    private val vpnRetries = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun fetchWithRetry(url: String, attempts: Int, conditional: Map<String, String> = emptyMap()): Fetched {
        var last = Fetched(0, null)
        var vpnUsed = false
        for (i in 1..attempts) {
            val ua = FetchPolicy.userAgent(i - 1)
            val direct = httpGet(url, userAgent = ua, extraHeaders = conditional)
            if (direct.code == 304) return direct
            if (direct.body != null && FetchPolicy.looksLikeSubscription(direct.body)) return direct
            // Приложение идёт мимо собственного VPN, поэтому при белых списках GitHub напрямую не открывается, хотя VPN работает.
            // Если напрямую не вышло (обрыв, блокировка, ошибка сервера, страница без конфигов) и VPN включён — повторяем через его локальный SOCKS.
            val port = LocalProxy.port
            val auth = LocalProxy.auth
            val vpnOn = port > 0 && auth != null
            last = direct
            if (FetchPolicy.tryVpn(direct.code) || direct.body != null) {
                val offlineNow = try { NetHooks.offline() } catch (_: Throwable) { false }
                if (vpnOn && VpnRetryBudget.allow(vpnUsed, vpnRetries.get(), offlineNow)) {
                    vpnUsed = true
                    vpnRetries.incrementAndGet()
                    val v = httpGetViaVpn(url, port, auth!!, ua)
                    if (v.body != null && FetchPolicy.looksLikeSubscription(v.body)) return v.copy(note = "через VPN")
                    last = Fetched(direct.code, direct.body, vpnTried = true, note = FetchPolicy.describe(direct.code, v.code, true))
                } else last = Fetched(direct.code, direct.body, vpnTried = vpnUsed, note = FetchPolicy.describe(direct.code, null, vpnUsed))
            }
            // страница без конфигов (заглушка, защита от ботов) остаётся как раньше «успехом с 0 конфигов», если VPN не помог
            if (last.body != null) return last
            // сети нет совсем — повторы бесполезны (после восстановления сети сбор запустится сам)
            if (try { NetHooks.offline() } catch (_: Throwable) { false }) return last
            if (i < attempts) delay(RETRY_DELAY_MS)
        }
        return last
    }

    /** Тот же запрос через внутренний SOCKS ядра (с паролем), когда напрямую не открывается. */
    private fun httpGetViaVpn(url: String, port: Int, auth: SocksAuth, ua: String = "Phoenix/1.0"): Fetched = try {
        LocalSocks.get(port, auth, url, connectTimeoutMs = TIMEOUT_MS, readTimeoutMs = TIMEOUT_MS, follow = true, userAgent = ua).use { r ->
            if (r.code == 200) {
                val text = readBounded(r.stream())
                if (text.length > 100) Fetched(200, text) else Fetched(200, null)
            } else Fetched(r.code, null)
        }
    } catch (_: Exception) {
        Fetched(0, null)
    }

    private fun readBounded(input: java.io.InputStream): String {
        val reader = input.bufferedReader()
        val sb = StringBuilder()
        val buf = CharArray(16 * 1024)
        while (sb.length < MAX_BODY_CHARS) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    private fun httpGet(url: String, proxy: java.net.Proxy? = null, userAgent: String = "Phoenix/1.0", extraHeaders: Map<String, String> = emptyMap()): Fetched {
        var conn: HttpURLConnection? = null
        return try {
            var current = url
            var hops = 0
            var code: Int
            var c: HttpURLConnection
            while (true) {
                c = (if (proxy != null) URL(current).openConnection(proxy) else URL(current).openConnection()) as HttpURLConnection
                conn?.disconnect()
                conn = c
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                // переходы ведём сами: система не следует из https в http и обратно (подписки часто перенаправляют так)
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "*/*")
                extraHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
                code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (FetchPolicy.isRedirect(code) && loc != null && hops < 5) {
                    current = URL(URL(current), loc).toString()
                    hops++
                    continue
                }
                break
            }
            if (code == 200) {
                // источник — чужой сервер: ответ ограничен по размеру, чтобы огромный файл не забил память
                val text = c.inputStream.bufferedReader().use { r ->
                    val sb = StringBuilder()
                    val buf = CharArray(16 * 1024)
                    while (sb.length < MAX_BODY_CHARS) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
                if (text.length > 100) Fetched(200, text, etag = c.getHeaderField("ETag").orEmpty(), lastMod = c.getHeaderField("Last-Modified").orEmpty()) else Fetched(200, null)
            } else {
                Fetched(code, null)
            }
        } catch (_: Exception) {
            Fetched(0, null)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private val lock = Mutex()

        const val SOURCES_LIST_URL =
            "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/sources.txt"

        // Весь прогон должен укладываться в ~10 минут, которые WorkManager даёт обычной задаче.
        const val MAX_FETCH_MS = 5 * 60 * 1000L
        const val TIMEOUT_MS = 30_000
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 5_000L
        const val PARALLEL = 6
        const val MAX_BODY_CHARS = 8_000_000
        const val MAX_PER_ADDRESS = 3

        val DEFAULT_BLACKLIST = listOf(
            "https://raw.githubusercontent.com/VansFenix/vpnparser/refs/heads/main/good.txt",
            "https://raw.githubusercontent.com/VansFenix/vpnparser/refs/heads/main/speed.txt",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/WaHapp",
        )
    }
}
