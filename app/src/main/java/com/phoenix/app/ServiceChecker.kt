package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Подбор серверов для цели с учётом уже проверенных сервисов: где нужный сервис открылся — первыми, где не открылся — последними. */
object ServiceMatch {
    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    /** Сервис, адрес которого совпадает с адресом проверки цели (по домену), либо null. */
    fun serviceFor(checkUrl: String, services: List<CheckService>): CheckService? {
        val h = hostOf(checkUrl)
        if (h.isEmpty()) return null
        return services.firstOrNull { ServiceVeto.sameSite(hostOf(it.url), h) }
    }

    /** Группа сервера для сервиса: открылся (0), отвечает (1), не проверялся (2), не открылся (3), без замера и страна выхода закрыта для сервиса (4). */
    fun groupOf(c: Candidate, checks: Map<String, CheckResult>, service: CheckService): Int {
        val ms = checks[CheckStore.keyOf(c.link)]?.services?.get(service.id)
        // страна по названию — только догадка (у «🇷🇺 LTE …» вход в России, выход за границей): реальный замер важнее;
        // без замера сервер из страны, где сервис закрыт (Claude из России), пробуем в самом конце
        if (ms == null && c.cc.isNotEmpty()) {
            val i = ServiceBlocks.index(service)
            if (i >= 0 && ServiceBlocks.status(c.cc)[i] == "blocked") return 4
        }
        if (ms == null) return 2
        return when {
            ms >= 0 -> 0
            ms == CheckServices.PARTIAL -> 1
            else -> 3
        }
    }

    /** Минимум замеренных сервисов, чтобы считать сервер узкоспециализированным («Telegram-only»: остальные сервисы не открылись). */
    const val NARROW_MIN_MEASURED = 3

    /** Результат замеров сервисов старше стольких дней не считается основанием отодвинуть сервер. */
    const val NARROW_TTL_DAYS = 3L

    /** Сервер измерен по нескольким сервисам, и ни один не открылся: для цели без своего сервиса (Chrome и т. п.) он идёт после остальных. */
    fun narrow(c: Candidate, checks: Map<String, CheckResult>, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        val r = checks[CheckStore.keyOf(c.link)] ?: return false
        if (r.staleFor(c.link)) return false // адрес сервера сменился после проверки
        val age = try { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(r.date), today) } catch (_: Exception) { return false }
        if (age > NARROW_TTL_DAYS) return false // старый результат: сервер мог измениться
        val s = r.services
        val measured = s.values.filter { it != CheckServices.UNSUPPORTED }
        return measured.size >= NARROW_MIN_MEASURED && measured.none { CheckServices.opened(it) }
    }

    /** Порядок сохраняется внутри групп (см. [groupOf]). */
    fun order(pool: List<Candidate>, checks: Map<String, CheckResult>, service: CheckService?, today: java.time.LocalDate = java.time.LocalDate.now()): List<Candidate> {
        if (service == null) return pool.sortedBy { narrow(it, checks, today) } // false раньше true; порядок внутри групп сохраняется
        return pool.sortedBy { groupOf(it, checks, service) }
    }

    /** Сколько подтверждённо рабочих для сервиса серверов достаточно, чтобы не брать заведомо закрытые. */
    const val STRICT_MIN_GOOD = 3

    /**
     * Жёсткий фильтр: если для сервиса уже есть минимум [STRICT_MIN_GOOD] подтверждённо рабочих серверов (группы 0 и 1),
     * серверы, где сервис не открылся (3) или страна выхода его закрывает (4), в этот список не попадают вовсе
     * (например, «[RU] … TG:blocked» для Telegram). Если подтверждённых мало — список не трогаем, чтобы не остаться без выбора.
     */
    fun strict(pool: List<Candidate>, checks: Map<String, CheckResult>, service: CheckService?): List<Candidate> {
        if (service == null) return pool
        val groups = pool.map { groupOf(it, checks, service) }
        if (groups.count { it <= 1 } < STRICT_MIN_GOOD) return pool
        return pool.filterIndexed { i, _ -> groups[i] <= 2 }
    }
}

/** Какие рабочие конфиги ещё не проверены по выбранным сервисам. */
object ServicePlan {
    /**
     * Из упорядоченных по рейтингу кандидатов — живые (по свежей проверке), у которых нет результата хотя бы по одному
     * выбранному сервису; по одному на сервер, не больше [limit].
     */
    fun pending(ranked: List<Candidate>, checks: Map<String, CheckResult>, services: List<CheckService>, limit: Int, all: Boolean = false): List<Candidate> {
        if (services.isEmpty() || limit <= 0) return emptyList()
        return ranked
            .filter { c ->
                val r = checks[CheckStore.keyOf(c.link)]
                c.alive && r != null && !r.dead && (all || services.any { it.id !in r.services })
            }
            .distinctBy { it.host.ifEmpty { it.link } }
            .take(limit)
    }

    /** Подпись результата для журнала: «TG✓ YT✗ QW?» по выбранным сервисам. */
    fun summary(result: CheckResult, services: List<CheckService>): String = result.label(services)
}

/**
 * Проверка конфигов через ядро. [probe] делает одну проверку (конфиг JSON, адрес) -> мс или -1;
 * вынесен в параметр, чтобы логику можно было проверять без Android.
 */
class ServiceChecker(
    private val probe: (config: String, url: String) -> Long,
    private val parallel: Int = 6,
    /** Сколько сервисов одного конфига проверять одновременно (1 — по одному, как раньше). */
    private val inner: Int = 1,
    /** (хост, порт) → IP сервера или null, если сервер не отвечает; по умолчанию без предпроверки */
    private val reach: (String, Int) -> String? = { h, _ -> h },
    /**
     * Мягкая проверка, если ядро не приняло ответ (оно считает успехом только 200/204, а сайты с защитой от ботов отвечают 403):
     * любой HTTP-ответ значит, что сервер до сайта дотянулся.
     */
    private val soft: ((link: String, url: String) -> Boolean)? = null,
    /** Страна выхода (для Claude и других сервисов, закрытых по региону): из закрытой страны сервис не засчитывается. */
    private val exit: ((link: String) -> String?)? = null,
    /** Страница сайта через сервер: true — «не поддерживается в вашей стране» (код ответа при этом 200); null — не удалось узнать. */
    private val region: ((link: String, url: String) -> Boolean?)? = null,
    /** Пользователь отметил, что на этом сервере сайт не открывается. */
    private val veto: ((link: String, url: String) -> Boolean)? = null,
    /** Просьба остановиться (кнопка «Остановить и сохранить»): новые серверы не берём, уже готовые результаты остаются. */
    private val stop: () -> Boolean = { false },
    /** Опытная запись в журнал (не влияет на итог): например, ответ дата-центра Telegram рядом с ответом страницы. */
    private val note: ((String) -> Unit)? = null,
) {
    /**
     * Конфиги одного сервера (общий хост, разные протоколы) проверяются по очереди, начиная с самого приоритетного,
     * и как только один ответил — остальные пропускаются (в результате их нет: «не проверялся»).
     * Разные серверы проверяются параллельно.
     */
    suspend fun check(
        links: List<String>,
        services: List<CheckService>,
        date: String,
        onResult: (key: String, result: CheckResult) -> Unit = { _, _ -> },
    ): Map<String, CheckResult> = coroutineScope {
        val gate = Semaphore(parallel)
        val groups = links.distinctBy { CheckStore.keyOf(it) }
            .groupBy { ConfigIdentity.host(it).ifEmpty { "?" + it } }
            .values
            .map { g -> g.sortedByDescending { LinkParser.parse(it)?.priority ?: 0 } }
        groups.map { group ->
            async {
                gate.withPermit {
                    withContext(Dispatchers.IO) {
                        val out = ArrayList<Pair<String, CheckResult>>()
                        for (link in group) {
                            if (stop()) break
                            val key = CheckStore.keyOf(link)
                            // onResult вызывается в IO-потоке: там же можно сразу писать результат в файл
                            val result = checkOne(link, services, date).also { onResult(key, it) }
                            out += key to result
                            if (!result.dead) break
                        }
                        out
                    }
                }
            }
        }.awaitAll().flatten().toMap()
    }

    private val DEAD_PROBES = 3
    private val REGION_READS = 3

    internal fun checkOne(link: String, services: List<CheckService>, date: String): CheckResult {
        val host = ConfigIdentity.address(link)
        val parsed = LinkParser.parse(link)
        var hosts: Map<String, String> = emptyMap()
        if (parsed?.host != null && parsed.port != null) {
            // сервер не открывает TCP — дальше проверять нечего (экономит и время, и батарею)
            val ip = (if (parsed.protocol == "hysteria2") ServerHosts.resolveOnly(parsed.host) else reach(parsed.host, parsed.port)) ?: return CheckResult(date, CheckServices.FAILED, emptyMap(), host = host)
            hosts = ServerHosts.hostsFor(parsed.host, ip)
        }
        val config = try {
            XrayConfig.probeConfig(link, hosts)
        } catch (e: UnsupportedLinkException) {
            return CheckResult(date, CheckServices.UNSUPPORTED, emptyMap(), host = host)
        }
        var base = probe(config, CheckServices.BASELINE_URL)
        val results: MutableMap<String, Long> = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val vetoedIds: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val country by lazy { exit?.invoke(link) }
        // Gemini отвечает 200 и из закрытых стран, а NotebookLM честно перенаправляет на «unsupported»: он служит проверкой того, как Google видит этот сервер
        // проверка страницы может не удаться (таймаут, перегрузка параллельных проверок): повторяем один раз, а если и тогда
        // ответа нет — сервис не считаем уверенно открытым (журнал 15.11: Turkey и Ukraine в проверке «✓», а NotebookLM через них закрыт)
        // результат один раз на адрес (NotebookLM — и оракул, и свой сервис: второй запуск ядра не нужен); повтор только у живого
        // конфига: у мёртвого лишняя попытка — это лишние 16 с на сервер без пользы
        val regionCache: MutableMap<String, Boolean?> = java.util.Collections.synchronizedMap(HashMap<String, Boolean?>())
        fun regionTry(url: String): Boolean? {
            val f = region ?: return false
            if (regionCache.containsKey(url)) return regionCache[url]
            val v = RegionVote.decide(if (base >= 0) REGION_READS else 1) { f(link, url) }
            regionCache[url] = v
            return v
        }
        val googleGeoClosed: Boolean? by lazy { regionTry(GoogleAi.GEO_ORACLE_URL) }
        fun one(s: CheckService) {
            val ms = probe(config, s.url)
            val r = when {
                ms >= 0 -> ms
                soft?.invoke(link, s.url) == true -> CheckServices.PARTIAL // отвечает, но не 200/204
                else -> CheckServices.FAILED
            }
            val geoClosed = r != CheckServices.FAILED && GeoGate.applies(s.url) && GeoGate.blocks(s.url, country)
            // сайт открылся с кодом 200, но это может быть страница «не поддерживается в вашей стране»: смотрим её содержимое
            val pageChecked = r != CheckServices.FAILED && !geoClosed && GeoGate.applies(s.url)
            val own = if (pageChecked) regionTry(GoogleAi.regionUrl(s.url)) else false
            val oracle = if (pageChecked && GoogleAi.needsOracle(s.url)) googleGeoClosed else false
            val pageClosed = own == true || oracle == true
            // ни «закрыто», ни «открыто» страница не сказала — отвечает, но уверенно открытым не считаем (≈, после подтверждённых ✓)
            val unsure = pageChecked && !pageClosed && (own == null || oracle == null)
            val vetoed = r != CheckServices.FAILED && veto?.invoke(link, s.url) == true
            // FAILED только из-за пометки пользователя (замер открылся, регион не закрыт): это не замер, в историю шансов он не идёт
            if (vetoed && !geoClosed && !pageClosed) vetoedIds += s.id
            results[s.id] = when {
                geoClosed || pageClosed || vetoed -> CheckServices.FAILED
                unsure && r >= 0 -> CheckServices.PARTIAL
                else -> r
            }
        }
        if (base < 0) {
            // gstatic может быть закрыт (белые списки), а нужное приложение через этот сервер работает (как в v2rayNG):
            // конфиг мёртв, только если не открылся ни один из первых выбранных сервисов
            for (s in services.take(DEAD_PROBES)) {
                one(s)
                if (CheckServices.opened(results[s.id])) break
            }
            val hit = results.values.firstOrNull { it >= 0 }
            base = when {
                hit != null -> hit
                results.values.any { it == CheckServices.PARTIAL } -> 0L
                else -> return CheckResult(date, CheckServices.FAILED, emptyMap(), host = host)
            }
            runAll(services.filter { it.id !in results }, ::one)
            return CheckResult(date, base, LinkedHashMap(results), host = host, vetoed = vetoedIds.toSet())
        }
        runAll(services, ::one)
        return CheckResult(date, base, LinkedHashMap(results), host = host, vetoed = vetoedIds.toSet())
    }

    /** Сервисы одного конфига: по одному или по [inner] одновременно (каждая проба — отдельное короткое ядро, 12 сервисов подряд занимали ~14 с на сервер). */
    private fun runAll(list: List<CheckService>, one: (CheckService) -> Unit) {
        if (inner <= 1 || list.size <= 1) { for (s in list) one(s); return }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(inner, list.size))
        try {
            list.map { s -> pool.submit { one(s) } }.forEach { try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } }
        } finally {
            pool.shutdown()
        }
    }
}
