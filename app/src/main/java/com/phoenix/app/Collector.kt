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

    /** [skipped] — сбор вообще не запускался (например, уже шёл другой): вызывающая сторона не должна принимать это за настоящий успех. */
    data class Outcome(val ok: Boolean, val summary: String, val skipped: Boolean = false)

    private data class Fetched(val code: Int, val body: String?, val vpnTried: Boolean = false, val note: String = "", val etag: String = "", val lastMod: String = "",
        /** не 0 — сеть источник не отдала (это был код ответа), а в конфиги взята его прежняя копия из кэша */
        val staleCode: Int = 0,
        /** источник отдал страницу без конфигов (портал, защита от ботов), взята прежняя копия: ни успех, ни неудача источника */
        val stub: Boolean = false,
        /** для кода 0: причина, время до ошибки и сколько байт успело прийти (например «таймаут чтения после 30000 мс, получено 812 КБ») */
        val diag: String = "",
        /** сколько байт пришло до ошибки (нужно FetchRoute: байты через VPN доказывают, что туннель работает) */
        val bytes: Long = 0)
    private class Pending(val url: String, val start: SourceState, val reactivated: Boolean)

    /** @param trigger кто запустил (alarm / worker / manual / catch-up) — попадает в журнал для диагностики */
    suspend fun run(trigger: String = "manual"): Outcome {
        // ручной сбор ждёт идущий плановый (его отменил REPLACE, но блокирующий ввод-вывод ещё держит замок); плановый при занятости пропускается
        val got = if (trigger == "manual") kotlinx.coroutines.withTimeoutOrNull(MANUAL_WAIT_MS) { lock.lock(); true } == true else lock.tryLock()
        if (!got) return Outcome(true, "Сборка уже выполняется", skipped = true)
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
        val remote = if (listUrl.isBlank()) null else fetchWithRetry(listUrl, 2).body?.takeIf { FetchPolicy.looksLikeSourceList(it) }
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
        val route = FetchRoute()
        val schedule = SourceSchedule(storage.sourceScheduleFile)
        val validators = SourceValidators(storage.sourceValidatorsFile)
        val manual = trigger == "manual"
        storage.sourceCacheDir.mkdirs()
        val fromCacheN = java.util.concurrent.atomic.AtomicInteger()
        val startedN = java.util.concurrent.atomic.AtomicInteger()
        val notModifiedN = java.util.concurrent.atomic.AtomicInteger()
        val viaVpnN = java.util.concurrent.atomic.AtomicInteger()
        val results: MutableList<Fetched?> = coroutineScope {
            toFetch.map { p ->
                async {
                    semaphore.withPermit {
                        progress(tr("Скачиваю источники: {} из {} ({})", startedN.incrementAndGet(), toFetch.size, try { java.net.URI(p.url).host ?: "" } catch (_: Throwable) { "" }))
                        val cacheFile = File(storage.sourceCacheDir, SourceCadence.hashOf(setOf(p.url)) + ".txt")
                        val cachedAny = if (cacheFile.exists()) try { cacheFile.readText() } catch (_: Exception) { null } else null
                        val cached = if (!manual) cachedAny else null
                        if (System.currentTimeMillis() > deadline) null
                        else if (!manual && schedule.skip(p.url, System.currentTimeMillis(), cached != null)) {
                            schedule.noteSkip(p.url)
                            fromCacheN.incrementAndGet()
                            Fetched(200, cached, note = "из кэша: по накопленной истории источник ещё не обновлялся")
                        } else fetchWithRetry(p.url, RETRIES, SourceValidators.requestHeaders(validators.get(p.url), cachedAny != null), route, deadline).let { f ->
                            // 304: сервер подтвердил, что источник не менялся — берём сохранённый ответ без скачивания
                            if (f.code == 304 && cachedAny != null) { notModifiedN.incrementAndGet(); Fetched(200, cachedAny, note = "из кэша: источник не изменился (304)") }
                            // источник не отдали (обрыв, блокировка, плохая сеть) — но прежняя копия есть: берём её, иначе один неудачный
                            // сбор (2 источника из 22) затирал сегодняшний пул, и при смене сервера оставалось перебирать ~20 конфигов
                            else if (f.body == null && cachedAny != null && FetchPolicy.looksLikeSubscription(cachedAny) &&
                                System.currentTimeMillis() - cacheFile.lastModified() < STALE_CACHE_MS
                            ) Fetched(200, cachedAny, f.vpnTried, "из кэша: источник не ответил, взята прежняя копия" + (if (f.code == 0 && f.diag.isNotEmpty()) " (${f.diag})" else ""), staleCode = f.code)
                            // источник ответил, но страницей без конфигов (портал входа, защита от ботов): прежняя копия лучше пустого ответа
                            else if (f.body != null && !FetchPolicy.looksLikeSubscription(f.body) && cachedAny != null && FetchPolicy.looksLikeSubscription(cachedAny) &&
                                System.currentTimeMillis() - cacheFile.lastModified() < STALE_CACHE_MS
                            ) Fetched(200, cachedAny, f.vpnTried, "из кэша: источник отдал страницу без конфигов, взята прежняя копия", stub = true)
                            else f
                        }.also { f ->
                            if (f.code == 200 && f.body != null && !f.note.startsWith("из кэша")) validators.put(p.url, f.etag, f.lastMod)
                            if (f.body != null && f.note == "через VPN") viaVpnN.incrementAndGet()
                            if (f.body != null && f.staleCode == 0 && !f.stub && FetchPolicy.looksLikeSubscription(f.body)) try { cacheFile.writeTextAtomic(f.body) } catch (_: Exception) { }
                        }
                    }
                }
            }.awaitAll()
        }.toMutableList()
        // VPN вернулся посреди сбора: источники, которым за время закрытого пути через VPN не пробовали, получают одну повторную попытку
        // (если до общего потолка осталось не меньше минуты); пробовавшиеся через VPN не повторяются
        run {
            val rPort = LocalProxy.port
            val rAuth = LocalProxy.auth
            if (!route.hasRetries() || rPort <= 0 || rAuth == null || (try { NetHooks.offline() } catch (_: Throwable) { false })) return@run
            controlVpn(route, rPort, rAuth) // VPN мог вернуться уже после последней загрузки
            val idx = route.takeRetries().mapNotNull { u -> toFetch.indexOfFirst { it.url == u }.takeIf { it >= 0 } }
            if (idx.isEmpty()) return@run
            storage.log("FETCH: повтор через VPN: ${idx.size} источников, которым через него не пробовали")
            val recovered = java.util.concurrent.atomic.AtomicInteger()
            coroutineScope {
                idx.map { i ->
                    async {
                        semaphore.withPermit {
                            val p = toFetch[i]
                            val old = results[i]
                            if (old == null || deadline - System.currentTimeMillis() < RETRY_MIN_LEFT_MS || route.mode() == FetchRoute.Mode.DIRECT_ONLY) return@withPermit
                            val v = httpGetViaVpn(p.url, rPort, rAuth, FetchPolicy.userAgent(0), deadline)
                            val ok = v.body != null && FetchPolicy.looksLikeSubscription(v.body)
                            route.vpnResult(ok, v.code, afterDirectFail = true, bytes = v.bytes, origin = originOf(p.url))
                            controlVpn(route, rPort, rAuth)
                            if (ok) {
                                recovered.incrementAndGet()
                                viaVpnN.incrementAndGet()
                                try { File(storage.sourceCacheDir, SourceCadence.hashOf(setOf(p.url)) + ".txt").writeTextAtomic(v.body!!) } catch (_: Exception) { }
                                results[i] = v.copy(note = "через VPN")
                            } else if (old.body == null) {
                                results[i] = old.copy(vpnTried = true, note = FetchPolicy.describe(old.code, v.code, null), diag = listOf(old.diag, v.diag).filter { it.isNotEmpty() }.joinToString("; "))
                            } else {
                                results[i] = old.copy(vpnTried = true)
                            }
                        }
                    }
                }.awaitAll()
            }
            storage.log("FETCH: повтор через VPN: скачано ${recovered.get()} из ${idx.size}")
        }
        val fromCache = fromCacheN.get()
        val notModified = notModifiedN.get()
        val viaVpn = viaVpnN.get()
        // источники, до которых не дошла очередь за общий потолок времени: раньше молча становились «пропущенными»
        val notReached = results.count { it == null }
        if (notReached > 0) storage.log("FETCH: не успел за ${MAX_FETCH_MS / 60_000} мин: $notReached источников")
        route.summary()?.let { storage.log(it) }
        if (parallel < PARALLEL) storage.log("FETCH: параллельных загрузок $parallel из $PARALLEL (ограничение нагрузки на сеть)")

        val okSources = ArrayList<SourceSet>()
        val bodies = ArrayList<String>()
        val bodyUrls = ArrayList<String>()
        // какие конфиги пришли из какого источника — для доли рабочих по каждой ссылке (старые записи остаются, пока источник не ответит заново)
        val members = LinkedHashMap<String, Set<String>>()
        if (storage.sourceMembersFile.exists()) {
            try {
                members += SourceStats.decode(storage.sourceMembersFile.readText())
            } catch (_: Exception) {
            }
        }
        // защищённые от автоудаления: свои ссылки, источники с высоким приоритетом и те, что пользователь сам вернул из чёрного списка;
        // встроенные адреса не защищены: мёртвое зеркало должно уходить само
        val goneProtected = mine.toSet() + KeepList(storage.keepFile).load() +
            SourcePriorities(storage.sourcePriorityFile).load().filterValues { it >= SourcePriority.HIGH }.keys
        val goneCandidates = ArrayList<SourcePolicy.Gone>()
        toFetch.zip(results).forEach { (p, res) ->
            if (res == null) {
                draft.sources += SourceLine(p.url, SourceKind.SKIPPED, reactivated = p.reactivated)
                return@forEach
            }
            val body = res.body
            if (body != null) {
                bodies += body
                bodyUrls += p.url
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
                if (res.staleCode != 0) {
                    // прежняя копия — не успех источника: счётчик неудач идёт как при обычной неудаче (мёртвый источник не должен жить вечно)
                    val ns = SourcePolicy.onFailure(p.start, res.staleCode, today)
                    if (SourcePolicy.definitiveGone(p.start, res.staleCode, today)) goneCandidates += SourcePolicy.Gone(p.url, res.staleCode, SourcePolicy.definitiveDate(p.start), p.url in goneProtected)
                    states[p.url] = if (FetchPolicy.uncertain(res.staleCode) && !res.vpnTried) p.start else ns
                } else if (res.stub) states[p.url] = p.start
                else states[p.url] = SourcePolicy.onSuccess(today)
                storage.log("OK: ${p.url} (${body.length} симв., ${keys.size} конфигов)" + (if (res.note.isNotEmpty()) " [${res.note}]" else "") + (if (keys.isEmpty()) " — в ответе нет конфигов (страница-заглушка или пустой источник)" else ""))
                draft.sources += SourceLine(p.url, SourceKind.OK, "${body.length} симв., ${keys.size} конфигов", p.reactivated)
            } else {
                val ns = SourcePolicy.onFailure(p.start, res.code, today)
                if (SourcePolicy.definitiveGone(p.start, res.code, today)) goneCandidates += SourcePolicy.Gone(p.url, res.code, SourcePolicy.definitiveDate(p.start), p.url in goneProtected)
                // ошибка, по которой нельзя судить, что источник умер (блокировка, защита от ботов, обрыв), без проверки через VPN в счёт не идёт
                val uncertain = FetchPolicy.uncertain(res.code) && !res.vpnTried
                states[p.url] = if (uncertain) p.start else ns
                storage.log("FAIL: ${p.url} (код ${res.code}${if (res.code == 200) " — ответ пустой или короче 100 символов" else ""}${if (res.code == 0 && res.diag.isNotEmpty()) ", ${res.diag}" else ""}, " + (if (uncertain) "в счёт неудач не идёт" else "неудача #${ns.fails}") + (if (res.note.isNotEmpty()) "; ${res.note}" else "") + ")")
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
        // автоудаление источников, которые два сбора подряд в разные дни отвечают 404/410 (здесь bodies не пуст: сеть есть)
        if (goneCandidates.isNotEmpty()) {
            val plan = SourcePolicy.planGone(goneCandidates)
            plan.warn.forEach { storage.log("FAIL: ${it.url} код ${it.code} второй раз — свой/важный источник не удаляю, проверь ссылку") }
            if (plan.remove.isNotEmpty()) {
                try { UndoStore(storage.undoDir).snapshot("Автоудаление источников (404/410): ${plan.remove.size}", listOf(storage.blacklistFile), System.currentTimeMillis()) } catch (_: Throwable) { }
                for (g in plan.remove) {
                    blacklist.add(g.url, "авто: код ${g.code} два сбора подряд (${g.prevDate}, $today)")
                    storage.log("REMOVED: ${g.url} — код ${g.code} два сбора подряд, в чёрном списке (вернуть: Источники → Чёрный список)")
                }
            }
            if (plan.postponed > 0) storage.log("REMOVED: за один сбор убираю не больше ${SourcePolicy.MAX_AUTO_REMOVE} источников, ещё ${plan.postponed} ждут следующего сбора")
        }
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
        // одно имя из разных источников — разные серверы (см. SourceTag), внутри одного источника — один и тот же
        val freshOwners = HashMap<String, String>()
        val fresh = SourceTag.apply(bodies.indices.map { i ->
            val all = expandBody(bodies[i])
            val cut = LiteProfile.capSource(all)
            if (cut.size < all.size) storage.log("LITE: из источника ${bodyUrls[i]} взял ${LiteProfile.CONFIGS_PER_SOURCE} из ${LiteProfile.linkCount(all)}")
            bodyUrls[i] to cut
        }, { CheckStore.keyOf(it) }, freshOwners)
        // рабочие конфиги прошлого сбора, которые источники сегодня не отдали, остаются в пуле на срок из настроек
        // (иначе неудачный сбор затирает пул целиком); новые строки идут первыми — при дубле остаётся свежая
        val carryDays = try { NetHooks.carryDays() } catch (_: Throwable) { 0 }
        val freshKeys = fresh.filter { it.contains("://") }.map { CheckStore.keyOf(it.trim()) }.toSet()
        val seenBefore = try { if (storage.carrySeenFile.exists()) CarryOver.decode(storage.carrySeenFile.readText()) else emptyMap() } catch (_: Exception) { emptyMap() }
        // источник каждого конфига прошлого пула: конфиги источников, которых в списке уже нет (убраны, в чёрном списке, автоудаление 404/410), не переносим
        val prevOwners = try { if (storage.carrySourcesFile.exists()) CarryOver.decodeOwners(storage.carrySourcesFile.readText()) else emptyMap() } catch (_: Exception) { emptyMap() }
        val stillListed = urls.toSet() - (try { blacklist.urls() } catch (_: Throwable) { emptySet() }) // в т.ч. убранные автоудалением в этом же сборе
        var droppedGone = 0
        val carried = try {
            val prev = storage.latestClean()?.readLines().orEmpty()
            val checks = CheckStore(storage.checksFile).load()
            CarryOver.select(
                prev, freshKeys, seenBefore, todayDate, carryDays, { CheckStore.keyOf(it) },
                sourceAlive = { k -> (prevOwners[k]?.let { it in stillListed } ?: true).also { if (!it) droppedGone++ } },
                alive = { k -> checks[k]?.dead != true },
            )
        } catch (_: Throwable) { emptyList() }
        try {
            val carriedKeys = carried.map { CheckStore.keyOf(it) }.toSet()
            storage.carrySeenFile.writeTextAtomic(CarryOver.encode(CarryOver.touch(seenBefore, freshKeys, carriedKeys, todayDate)))
            storage.carrySourcesFile.writeTextAtomic(CarryOver.encodeOwners(CarryOver.nextOwners(freshOwners, prevOwners, carriedKeys)))
        } catch (_: Throwable) { }
        if (droppedGone > 0) storage.log("CARRY: не перенесено $droppedGone конфигов источников, которых больше нет в списке")
        if (carried.isNotEmpty()) storage.log("CARRY: из прошлого сбора оставлено ${carried.size} рабочих конфигов (срок $carryDays дн.), новых из источников ${freshKeys.size}")
        val lines = fresh + carried
        val cleaned = Dedup.clean(lines)
        val topAll = Dedup.topPerAddress(cleaned, MAX_PER_ADDRESS)
        val top = if (LiteProfile.ON && topAll.size > LiteProfile.CONFIGS_TOTAL) liteCapTotal(topAll, freshOwners, prevOwners) else topAll
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
        NetHooks.mem("сбор: конец")
        progress(summary)
        return Outcome(true, summary)
    }

    /** Подписка может быть целиком в base64 — тогда раскодируем. */
    /** Lite: общий предел с сохранением текущего сервера, живых по проверке и источников с высоким приоритетом (см. [LiteProfile.capTotalRanked]). */
    private fun liteCapTotal(all: List<ParsedLink>, freshOwners: Map<String, String>, prevOwners: Map<String, String>): List<ParsedLink> {
        val checks = try { CheckStore(storage.checksFile).load() } catch (_: Throwable) { emptyMap() }
        val prio = try { AutoPriority.effective(SourcePriorities(storage.sourcePriorityFile).load(), SourcePriorities(storage.sourceAutoPriorityFile).load()) } catch (_: Throwable) { emptyMap() }
        val currentKey = try { storage.lastConnection()?.first?.let { CheckStore.keyOf(it) } } catch (_: Throwable) { null }
        // ключ — как у строки в файле: ссылка вместе с #именем (так он записан в проверках и владельцах)
        fun key(l: ParsedLink) = CheckStore.keyOf(l.urlPart + (if (l.name.isNotEmpty()) "#" + l.name else ""))
        fun alive(l: ParsedLink) = checks[key(l)]?.let { !it.dead } == true
        val kept = LiteProfile.capTotalRanked(all, { l ->
            val k = key(l)
            LiteProfile.keepRank(k == currentKey, alive(l), prio[freshOwners[k] ?: prevOwners[k]] ?: SourcePriority.NORMAL)
        })
        val liveAll = all.count { alive(it) }
        val liveKept = kept.count { alive(it) }
        storage.log("LITE: всего оставил ${kept.size} из ${all.size} (живых по проверке — $liveAll, " + (if (liveKept == liveAll) "все оставлены" else "оставлено $liveKept") + ")")
        return kept
    }

    private fun expandBody(body: String): List<String> {
        val text = if (body.contains("://")) body else (LinkParser.decodeBase64(body) ?: body)
        return text.lines()
    }

    private suspend fun fetchWithRetry(url: String, attempts: Int, conditional: Map<String, String> = emptyMap(), route: FetchRoute = FetchRoute(), deadline: Long = Long.MAX_VALUE): Fetched {
        var last = Fetched(0, null)
        val origin = originOf(url)
        var vpnUsed = false
        for (i in 1..attempts) {
            val ua = FetchPolicy.userAgent(i - 1)
            // Приложение идёт мимо собственного VPN, поэтому при белых списках GitHub напрямую не открывается, хотя VPN работает.
            // Порядок путей (напрямую или через VPN) решает FetchRoute по фактам этого сбора; на источник не больше одного повтора через VPN.
            val port = LocalProxy.port
            val auth = LocalProxy.auth
            val vpnOn = port > 0 && auth != null
            val offlineNow = try { NetHooks.offline() } catch (_: Throwable) { false }
            // путь через VPN закрыт: связь могла вернуться посреди сбора, поэтому не раньше чем через 15 с перед загрузкой проверяем ещё раз
            if (vpnOn && !offlineNow && route.mode() == FetchRoute.Mode.DIRECT_ONLY) controlVpn(route, port, auth!!)
            val order = route.order(vpnOn && !offlineNow && !vpnUsed)
            var vpnFirstFailed: Fetched? = null
            if (order == FetchRoute.Order.VPN_THEN_DIRECT) {
                vpnUsed = true
                route.noteVpnTried(url)
                val v = httpGetViaVpn(url, port, auth!!, ua, deadline)
                val ok = v.body != null && FetchPolicy.looksLikeSubscription(v.body)
                route.vpnResult(ok, v.code, afterDirectFail = false, bytes = v.bytes, origin = origin)
                controlVpn(route, port, auth)
                if (ok) return v.copy(note = "через VPN")
                vpnFirstFailed = v
            }
            val direct = httpGet(url, userAgent = ua, extraHeaders = conditional, deadline = deadline)
            if (direct.code == 304) return direct
            if (direct.body != null && FetchPolicy.looksLikeSubscription(direct.body)) {
                if (vpnFirstFailed == null) route.directOpened()
                return direct
            }
            last = direct
            if (vpnFirstFailed != null) {
                // через VPN не вышло, напрямую тоже: зеркало могло открываться только напрямую, но и так нет
                last = Fetched(direct.code, direct.body, vpnTried = true, note = FetchPolicy.describe(direct.code, vpnFirstFailed.code, null), diag = pathDiag(direct, vpnFirstFailed), bytes = direct.bytes)
            } else if (FetchPolicy.tryVpn(direct.code) || direct.body != null) {
                if (order == FetchRoute.Order.DIRECT_THEN_VPN) {
                    vpnUsed = true
                    route.noteVpnTried(url)
                    val v = httpGetViaVpn(url, port, auth!!, ua, deadline)
                    val ok = v.body != null && FetchPolicy.looksLikeSubscription(v.body)
                    route.vpnResult(ok, v.code, afterDirectFail = true, bytes = v.bytes, origin = origin)
                    controlVpn(route, port, auth)
                    if (ok) return v.copy(note = "через VPN")
                    last = Fetched(direct.code, direct.body, vpnTried = true, note = FetchPolicy.describe(direct.code, v.code, null), diag = pathDiag(direct, v), bytes = direct.bytes)
                } else {
                    val reason = if (vpnUsed) null else route.noVpnReason(vpnOn, offlineNow)
                    // путь через VPN закрыт, источник через него не пробовали: после возвращения VPN ему положена одна повторная попытка
                    if (!vpnUsed && reason == FetchRoute.REASON_VPN_DEAD && direct.code == 0) route.noteSkippedVpn(url)
                    last = Fetched(direct.code, direct.body, vpnTried = vpnUsed, note = FetchPolicy.describe(direct.code, null, reason), diag = direct.diag, bytes = direct.bytes)
                }
            }
            // страница без конфигов (заглушка, защита от ботов) остаётся как раньше «успехом с 0 конфигов», если VPN не помог
            if (last.body != null) return last
            // сети нет совсем — повторы бесполезны (после восстановления сети сбор запустится сам)
            if (try { NetHooks.offline() } catch (_: Throwable) { false }) return last
            if (i < attempts) delay(RETRY_DELAY_MS)
        }
        return last
    }

    /** Адрес для контрольной проверки VPN: «схема://узел/» источника; пусто, если разобрать не удалось. */
    private fun originOf(url: String): String = try { java.net.URI(url).let { "${it.scheme}://${it.authority}/" } } catch (_: Throwable) { "" }

    /** Для строки FAIL с кодом 0: что случилось на каждом пути. Каждая причина уже подписана («напрямую: …», «через VPN: …»). */
    private fun pathDiag(direct: Fetched, vpn: Fetched): String = listOf(direct.diag, vpn.diag).filter { it.isNotEmpty() }.joinToString("; ")

    /**
     * Контрольная проверка VPN: короткий запрос через туннель к узлу, уже ответившему через VPN (или к адресу проверки связи). Любой HTTP-ответ — туннель жив.
     * Когда она нужна (после серии неудач, а при DIRECT_ONLY — повторно не раньше чем через 15 с) и лимиты (не больше 5 за сбор) решает FetchRoute.
     */
    private fun controlVpn(route: FetchRoute, port: Int, auth: SocksAuth) {
        val target = route.takeControl(Baseline.PRIMARY) ?: return
        val alive = try {
            LocalSocks.get(port, auth, target, connectTimeoutMs = CONTROL_TIMEOUT_MS, readTimeoutMs = CONTROL_TIMEOUT_MS, follow = false).use { it.code > 0 }
        } catch (_: Exception) {
            false
        }
        storage.log(
            when (route.controlResult(alive)) {
                FetchRoute.Control.ALIVE -> "FETCH: VPN жив (контроль ок) — источники с кодом 0 плохие сами по себе"
                FetchRoute.Control.RETURNED -> "FETCH: VPN снова отвечает — возвращаю загрузки через VPN"
                FetchRoute.Control.WENT_DIRECT_ONLY -> "FETCH: контрольная проверка через VPN не ответила — дальше только напрямую"
                FetchRoute.Control.STILL_DIRECT_ONLY -> "FETCH: повторная проверка VPN не ответила — остаюсь на прямых загрузках"
            }
        )
    }

    /** Тот же запрос через внутренний SOCKS ядра (с паролем), когда напрямую не открывается. */
    private fun httpGetViaVpn(url: String, port: Int, auth: SocksAuth, ua: String = "Phoenix/1.0", deadline: Long = Long.MAX_VALUE): Fetched {
        val t0 = System.currentTimeMillis()
        var counted: CountingInput? = null
        return try {
            LocalSocks.get(port, auth, url, connectTimeoutMs = TIMEOUT_MS, readTimeoutMs = TIMEOUT_MS, follow = true, userAgent = ua).use { r ->
                if (r.code == 200) {
                    val cin = CountingInput(r.stream())
                    counted = cin
                    val text = readBounded(cin)
                    if (text.length > 100) Fetched(200, text) else Fetched(200, null)
                } else Fetched(r.code, null)
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            val n = counted?.count ?: 0L
            Fetched(0, null, diag = "через VPN: " + FetchFailure.describe(e, now - t0, n, afterLimit = now > deadline, timeoutMs = TIMEOUT_MS.toLong()), bytes = n)
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val reader = input.bufferedReader()
        val sb = StringBuilder()
        val buf = CharArray(16 * 1024)
        val maxBody = LiteProfile.bodyChars(MAX_BODY_CHARS)
        while (sb.length < maxBody) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    private fun httpGet(url: String, proxy: java.net.Proxy? = null, userAgent: String = "Phoenix/1.0", extraHeaders: Map<String, String> = emptyMap(), deadline: Long = Long.MAX_VALUE): Fetched {
        var conn: HttpURLConnection? = null
        val t0 = System.currentTimeMillis()
        var counted: CountingInput? = null
        var blockedRedirect = false
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
                    val next = URL(URL(current), loc).toString()
                    if (!FetchPolicy.safeRedirect(next)) { code = 0; blockedRedirect = true; break } // переход на локальный адрес или не http(s) — как обрыв
                    current = next
                    hops++
                    continue
                }
                break
            }
            if (code == 200) {
                // источник — чужой сервер: ответ ограничен по размеру, чтобы огромный файл не забил память
                val cin = CountingInput(c.inputStream)
                counted = cin
                val text = cin.bufferedReader().use { r ->
                    val sb = StringBuilder()
                    val buf = CharArray(16 * 1024)
                    val maxBody = LiteProfile.bodyChars(MAX_BODY_CHARS)
                    while (sb.length < maxBody) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
                if (text.length > 100) Fetched(200, text, etag = c.getHeaderField("ETag").orEmpty(), lastMod = c.getHeaderField("Last-Modified").orEmpty()) else Fetched(200, null)
            } else if (blockedRedirect) {
                Fetched(0, null, diag = "напрямую: " + FetchFailure.describe("переход на запрещённый адрес", System.currentTimeMillis() - t0, 0))
            } else {
                Fetched(code, null)
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            val n = counted?.count ?: 0L
            Fetched(0, null, diag = "напрямую: " + FetchFailure.describe(e, now - t0, n, afterLimit = now > deadline, timeoutMs = TIMEOUT_MS.toLong()), bytes = n)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private val lock = Mutex()

        const val SOURCES_LIST_URL =
            "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/sources.txt"

        // Весь прогон должен укладываться в ~10 минут, которые WorkManager даёт обычной задаче.
        const val MANUAL_WAIT_MS = 120_000L
        const val MAX_FETCH_MS = 5 * 60 * 1000L
        /** Насколько старую копию источника ещё можно взять вместо недоступного: дальше конфиги скорее мертвы, чем нужны */
        const val STALE_CACHE_MS = 7L * 24 * 3600 * 1000
        const val TIMEOUT_MS = 30_000
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 5_000L
        /** Таймаут короткого запроса контрольной проверки VPN (не путать с TIMEOUT_MS скачивания источника). */
        const val CONTROL_TIMEOUT_MS = 10_000
        /** Повторную попытку через VPN начинают, только если до общего потолка сбора осталось не меньше этого. */
        const val RETRY_MIN_LEFT_MS = 60_000L
        const val PARALLEL = 6
        const val MAX_BODY_CHARS = 3_000_000
        const val MAX_PER_ADDRESS = 3

        val DEFAULT_BLACKLIST = listOf(
            "https://raw.githubusercontent.com/VansFenix/vpnparser/refs/heads/main/good.txt",
            "https://raw.githubusercontent.com/VansFenix/vpnparser/refs/heads/main/speed.txt",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/WaHapp",
        )
    }
}
