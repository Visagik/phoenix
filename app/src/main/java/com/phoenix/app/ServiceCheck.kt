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

/** Сервис, доступность которого проверяется через конфиг. */
data class CheckService(
    val id: String,
    val title: String,
    val url: String,
    /** Короткая подпись для колонки (YT, YS…); у сервисов не из целей совпадает с кодом. */
    val short: String = id,
    /** Код встроенного сервиса с тем же адресом: старые результаты проверок лежат под ним. */
    val legacyId: String = "",
)

/** Итог проверки сервиса через сервер: открыт, частично, закрыт, ещё не проверялся. */
enum class ServiceStatus { OPEN, PARTIAL, CLOSED, UNKNOWN }

/** Строка таблицы «цели × сервисы»: название цели, сервер и состояния сервисов в порядке столбцов. */
data class MatrixRow(val label: String, val server: String, val statuses: List<ServiceStatus>)

object CheckServices {
    /**
     * Ядро (measureOutboundDelay) считает успехом только ответ 200 или 204 без ошибок, поэтому для сервисов
     * с защитой от ботов (Claude, Qwen) результат «✗» может быть ложным. Адреса можно поменять здесь.
     */
    val ALL = listOf(
        CheckService("TG", "Telegram", "https://web.telegram.org/"),
        CheckService("QW", "Qwen", "https://chat.qwen.ai/"),
        CheckService("CL", "Claude", "https://claude.ai/"),
        CheckService("YT", "YouTube", "https://www.youtube.com/generate_204"),
        CheckService("ST", "Steam", "https://store.steampowered.com/"),
    )

    /** Встроенные и свои сервисы (свои хранятся отдельно для каждого режима). */
    fun all(custom: List<CheckService>): List<CheckService> = ALL + custom

    /** Контроль: если даже он не открывается, конфиг мёртв и сервисы можно не проверять. */
    const val BASELINE_URL = Baseline.PRIMARY

    const val UNSUPPORTED = -2L
    const val FAILED = -1L

    /** Сайт отвечает, но не 200/204 (защита от ботов, редирект): сервер до него дотянулся, но полноценно ли открывается — неизвестно. */
    const val PARTIAL = -3L

    /** Сервис считается открывшимся: полностью (мс) или частично (отвечает). */
    fun opened(ms: Long?): Boolean = ms != null && (ms >= 0 || ms == PARTIAL)
}

/**
 * Итог проверки одного конфига. Значения сервисов: мс, либо -1 (не открылось).
 * [baselineMs] — реальная задержка HTTPS-запроса через конфиг (не ping). [mbps] — скорость скачивания, -1 если не мерили.
 * [host] — адрес сервера на момент проверки: если конфиг тот же по имени, а адрес сменился, результат устарел.
 */
data class CheckResult(
    val date: String,
    val baselineMs: Long,
    val services: Map<String, Long>,
    val mbps: Double = -1.0,
    val host: String = "",
) {
    val dead: Boolean get() = baselineMs < 0

    /** Результат по сервису: свой, а если нет — старый, записанный под кодом встроенного сервиса с тем же адресом. */
    fun ms(s: CheckService): Long? = services[s.id] ?: s.legacyId.takeIf { it.isNotEmpty() }?.let { services[it] }

    fun status(s: CheckService): ServiceStatus = status(ms(s))

    private fun status(ms: Long?): ServiceStatus {
        ms ?: return ServiceStatus.UNKNOWN
        return when {
            ms >= 0 -> ServiceStatus.OPEN
            ms == CheckServices.PARTIAL -> ServiceStatus.PARTIAL
            else -> ServiceStatus.CLOSED
        }
    }

    /** Состояние одного сервиса для таблицы «цель × сервис». */
    fun status(serviceId: String): ServiceStatus {
        val ms = services[serviceId] ?: return ServiceStatus.UNKNOWN
        return when {
            ms >= 0 -> ServiceStatus.OPEN
            ms == CheckServices.PARTIAL -> ServiceStatus.PARTIAL
            else -> ServiceStatus.CLOSED
        }
    }

    /** Адрес сервера сменился после проверки (конфиг найден по имени). */
    fun staleFor(link: String): Boolean {
        val now = ConfigIdentity.address(link)
        return host.isNotEmpty() && now.isNotEmpty() && host != now
    }

    /** «340 мс · 12.4 Мбит/с» (что известно). */
    fun speedText(): String {
        val parts = ArrayList<String>()
        if (baselineMs >= 0) parts += "$baselineMs мс"
        if (mbps >= 0) parts += SpeedTest.format(mbps)
        return parts.joinToString(" · ")
    }

    /** Подпись с названиями: «Claude ✓ · Qwen ≈ · YouTube ✗» — понятнее кодов; «?» — ещё не проверялся. */
    fun labelNamed(selected: List<CheckService>, odds: ((String) -> Int?)? = null): String = selected.joinToString(" · ") { s ->
        val ms = ms(s)
        s.title + " " + (odds?.invoke(s.id)?.let { "$it% " } ?: "") + when {
            ms == null -> "?"
            ms >= 0 -> "✓"
            ms == CheckServices.PARTIAL -> "≈"
            else -> "✗"
        }
    }

    fun label(selected: List<CheckService>): String = when (baselineMs) {
        CheckServices.UNSUPPORTED -> "не поддерживается"
        CheckServices.FAILED -> "не отвечает"
        else -> selected.joinToString(" ") { s ->
            val ms = ms(s)
            s.short + when {
                ms == null -> "?"
                ms >= 0 -> "✓"
                ms == CheckServices.PARTIAL -> "≈"
                else -> "✗"
            }
        }
    }
}

/** Результаты проверок, ключ — отпечаток ссылки без имени. */
class CheckStore private constructor(private val file: File) {
    // файл разбирается заново только если он изменился (проверки пишут результат за результатом, а читают десятки мест)
    private var cached: Map<String, CheckResult>? = null
    private var cachedStamp = ""

    private fun stamp() = if (file.exists()) "${file.length()}:${file.lastModified()}" else "none"

    @Synchronized
    fun load(): Map<String, CheckResult> {
        val s = stamp()
        cached?.let { if (cachedStamp == s) return it }
        return parse().also { cached = it; cachedStamp = s }
    }

    private fun parse(): Map<String, CheckResult> {
        if (!file.exists()) return emptyMap()
        val map = LinkedHashMap<String, CheckResult>()
        file.forEachLine { line ->
            val p = line.split('\t')
            if (p.size < 3) return@forEachLine
            val base = p[2].toLongOrNull() ?: return@forEachLine
            val mbps = p.getOrNull(4)?.toDoubleOrNull() ?: -1.0
            val host = p.getOrElse(5) { "" }
            val services = p.getOrElse(3) { "" }.split(';').mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq <= 0) null else kv.substring(0, eq) to (kv.substring(eq + 1).toLongOrNull() ?: return@mapNotNull null)
            }.toMap()
            map[p[0]] = CheckResult(p[1], base, services, mbps, host)
        }
        return map
    }

    /** Прочитать–изменить–записать одним действием под замком: два потока не затирают правки друг друга (находка 3.4). */
    @Synchronized
    fun modify(recordOdds: Boolean = true, build: (Map<String, CheckResult>) -> Map<String, CheckResult>) {
        val updates = build(load())
        if (updates.isNotEmpty()) putAll(updates, recordOdds)
    }

    @Synchronized
    fun putAll(results: Map<String, CheckResult>, recordOdds: Boolean = true) {
        val merged = LinkedHashMap(load())
        val events = ArrayList<HistEvent>()
        for ((k, r) in results) {
            val old = merged[k]
            if (recordOdds && !r.dead && r.services.isNotEmpty() && (old == null || old.date != r.date || old.services != r.services)) {
                for ((sid, ms) in r.services) {
                    if (ms == CheckServices.UNSUPPORTED) continue
                    events += HistEvent(k + "|" + sid, ODDS_CTX, CheckServices.opened(ms))
                }
            }
            // повторная проверка сервисов не должна стирать ранее измеренную скорость (если сервер тот же)
            merged[k] = if (old != null && r.mbps < 0 && old.mbps >= 0 && (r.host.isEmpty() || r.host == old.host)) {
                r.copy(mbps = old.mbps, host = old.host.ifEmpty { r.host })
            } else r
        }
        val text = merged.entries.joinToString("\n") { (k, r) ->
            k + "\t" + r.date + "\t" + r.baselineMs + "\t" + r.services.entries.joinToString(";") { it.key + "=" + it.value } +
                "\t" + r.mbps + "\t" + r.host
        } + "\n"
        file.parentFile?.mkdirs()
        file.writeTextAtomic(text)
        cached = merged
        cachedStamp = stamp()
        if (events.isNotEmpty()) try {
            oddsHistory().record(events)
        } catch (_: Throwable) {
        }
    }

    private fun oddsHistory() = ConfigHistory(File(file.parentFile, "service_history.tsv"), maxLines = 12_000, daypart = false)

    /** Шанс, что сервис [sid] откроется через конфиг [key]: по накопленным проверкам, а без своих данных — по среднему для сервиса. */
    fun odds(): (String, String) -> Int? {
        val all = try { oddsHistory().load() } catch (_: Throwable) { emptyMap() }
        if (all.isEmpty()) return { _, _ -> null }
        val bySvc = HashMap<String, IntArray>()
        for ((k, st) in all) {
            val sid = k.substringBefore('\t').substringAfter('|', "")
            val a = bySvc.getOrPut(sid) { IntArray(2) }
            a[0] += st.ok
            a[1] += st.fail
        }
        return { key, sid ->
            val st = all[ConfigHistory.keyOf("$key|$sid", ODDS_CTX)]
            val avg = bySvc[sid]
            if (st == null && avg == null) null
            else ServiceOdds.percent(st?.ok ?: 0, st?.fail ?: 0, ServiceOdds.prior(avg?.get(0) ?: 0, avg?.get(1) ?: 0))
        }
    }

    /** Записывает скорость к результату конфига (создаёт запись, если проверки сервисов ещё не было). */
    @Synchronized
    fun putSpeed(key: String, speed: SpeedResult, date: String, host: String) {
        val old = load()[key]
        val base = old ?: CheckResult(date, speed.ttfbMs, emptyMap(), host = host)
        putAll(mapOf(key to base.copy(date = date, mbps = speed.mbps, host = host)), recordOdds = false)
    }

    companion object {
        const val ODDS_CTX = "all"
        private val cache = java.util.concurrent.ConcurrentHashMap<String, CheckStore>()

        /** Один объект на файл (см. TargetStore): фоновая проверка и экран не затирают результаты друг друга. */
        operator fun invoke(file: File): CheckStore = cache.getOrPut(file.absolutePath) { CheckStore(file) }

        /** Ключ — личность конфига (протокол + имя), а не адрес: результаты переживают смену сервера. */
        fun keyOf(link: String): String {
            keyCache.get(link)?.let { return it }
            val digest = MessageDigest.getInstance("SHA-1").digest(ConfigIdentity.key(link).toByteArray())
            val k = digest.joinToString("") { "%02x".format(it) }.take(12)
            keyCache.put(link, k)
            return k
        }

        /** Ключ считается на каждый конфиг при каждой перерисовке (HomePanel, «Серверы»): без кэша ANR 17.11 (urlDecode + SHA-1 в главном потоке). */
        private const val KEY_CACHE_MAX = 16_384
        private val keyCache = BoundedCache<String, String>(KEY_CACHE_MAX)
    }
}

/**
 * Свои сервисы пользователя (название и адрес). Результат проверки пишется к каждому конфигу под коротким кодом сервиса,
 * как и у встроенных. Файл: код, название, адрес (через табуляцию).
 */
class CustomServices(private val file: File) {
    fun load(): List<CheckService> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3 || p[0].isBlank() || p[2].isBlank()) null else CheckService(p[0], p[1], p[2])
        }
    }

    /** @return null — добавлено, иначе текст ошибки */
    @Synchronized
    fun add(title: String, address: String): String? {
        val name = title.trim().replace('\t', ' ')
        if (name.isEmpty()) return "Введите название"
        val url = normalizeUrl(address) ?: return "Введите адрес сайта, например https://example.com/"
        val current = load()
        if (current.any { it.url.equals(url, true) }) return "Такой адрес уже есть"
        val id = freeId(name, current.map { it.id }.toSet() + CheckServices.ALL.map { it.id })
        save(current + CheckService(id, name, url))
        return null
    }

    @Synchronized
    fun remove(id: String) = save(load().filter { it.id != id })

    private fun save(list: List<CheckService>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomic(list.joinToString("\n") { it.id + "\t" + it.title + "\t" + it.url } + if (list.isEmpty()) "" else "\n")
    }

    companion object {
        /** Адрес или просто домен → полный адрес; null, если это не похоже на сайт. */
        fun normalizeUrl(input: String): String? {
            val t = input.trim()
            if (t.isEmpty() || t.contains(' ')) return null
            val full = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
            val host = full.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            if (host.isEmpty() || !host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
            return if (full.substringAfter("://").contains('/')) full else "$full/"
        }

        /** Короткий код для подписи у конфига: две-три буквы из названия, без повторов; не содержит «;» и «=». */
        fun freeId(title: String, taken: Set<String>): String {
            val letters = title.filter { it.isLetterOrDigit() }.ifEmpty { "S" }
            val base = letters.take(2).uppercase()
            if (base !in taken) return base
            for (n in 2..99) if ("$base$n" !in taken) return "$base$n"
            return "$base${taken.size}"
        }
    }
}

/** Встроенные сервисы, которые пользователь убрал из списка (не нужны). Свои сервисы просто удаляются. */
class HiddenServices(private val file: File) {
    fun load(): Set<String> = if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()

    @Synchronized
    fun hide(id: String) = save(load() + id)

    @Synchronized
    fun restoreAll() = save(emptySet())

    private fun save(ids: Set<String>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomic(ids.joinToString("\n") + if (ids.isEmpty()) "" else "\n")
    }
}

/** Выбранные для проверки сервисы (по умолчанию все встроенные, кроме убранных, и свои). */
class CheckSelection(
    private val file: File,
    private val custom: () -> List<CheckService> = { emptyList() },
    private val hidden: () -> Set<String> = { emptySet() },
    /** Если задан, сервисы строятся из целей (колонка = цель), встроенные и свои не используются. */
    private val targets: (() -> List<Target>)? = null,
) {
    /** Все сервисы, которые показываются в списке. */
    fun all(): List<CheckService> {
        targets?.let { return TargetServices.from(it()) }
        val gone = hidden()
        return CheckServices.ALL.filter { it.id !in gone } + custom()
    }

    fun selected(): List<CheckService> {
        val all = all()
        if (targets != null) return all
        if (!file.exists()) return all
        val ids = file.readLines().map { it.trim() }.toSet()
        return all.filter { it.id in ids }
    }

    fun set(ids: Collection<String>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomic(ids.joinToString("\n") + "\n")
    }
}

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
            val i = ServiceBlocks.LABELS.indexOf(service.id)
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
            return CheckResult(date, base, LinkedHashMap(results), host = host)
        }
        runAll(services, ::one)
        return CheckResult(date, base, LinkedHashMap(results), host = host)
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

/** Сервисы, построенные из целей: колонка = цель, адрес проверки — адрес цели, подпись — сокращение названия. */
object TargetServices {
    fun from(targets: List<Target>): List<CheckService> {
        val uniq = targets.filter { it.checkUrl.isNotBlank() }.distinctBy { it.checkUrl }
        val shorts = TargetAbbrev.codes(uniq.map { it.label })
        return uniq.mapIndexed { i, t ->
            CheckService(t.id, t.label, t.checkUrl, shorts[i], CheckServices.ALL.firstOrNull { it.url == t.checkUrl }?.id.orEmpty())
        }
    }
}

/**
 * Короткая подпись названия цели: две и больше части (по пробелам и заглавным буквам) — первые буквы первых двух (YouTube → YT),
 * одна часть — первые две буквы (Steam → ST). Совпали — у совпавших три буквы, потом цифра.
 */
object TargetAbbrev {
    fun of(label: String): String {
        val clean = label.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        if (clean.isEmpty()) return "?"
        val parts = ArrayList<String>()
        for (word in clean.split(' ').filter { it.isNotEmpty() }) {
            var cur = StringBuilder()
            for ((i, ch) in word.withIndex()) {
                val boundary = i > 0 && ch.isUpperCase() && !word[i - 1].isUpperCase()
                if (boundary && cur.isNotEmpty()) { parts += cur.toString(); cur = StringBuilder() }
                cur.append(ch)
            }
            if (cur.isNotEmpty()) parts += cur.toString()
        }
        ALIASES[clean.replace(" ", "").lowercase()]?.let { return it }
        val code = if (parts.size >= 2) parts[0].take(1) + parts[1].take(1) else firstAndConsonant(clean.replace(" ", ""))
        return code.uppercase()
    }

    /** Привычные коды известных сервисов: узнаются сразу (TG, DC, WA…). */
    private val ALIASES = mapOf(
        // мессенджеры и соцсети
        "telegram" to "TG", "telegramx" to "TG", "discord" to "DC", "whatsapp" to "WA", "instagram" to "IG", "facebook" to "FB",
        "twitter" to "TW", "x" to "X", "tiktok" to "TT", "snapchat" to "SC", "pinterest" to "PI", "reddit" to "RD", "linkedin" to "LI",
        "viber" to "VB", "signal" to "SG", "skype" to "SK", "zoom" to "ZM", "twitch" to "TV", "threads" to "TH", "vk" to "VK", "вконтакте" to "VK",
        // видео и музыка
        "youtube" to "YT", "youtubemusic" to "YM", "spotify" to "SP", "netflix" to "NF", "soundcloud" to "SC2", "kinopoisk" to "KP", "кинопоиск" to "KP",
        // нейросети
        "claude" to "CL", "chatgpt" to "GPT", "gpt" to "GPT", "openai" to "OA", "gemini" to "GM", "copilot" to "CP", "grok" to "GK", "deepseek" to "DS",
        "qwen" to "QW", "kimi" to "KM", "mistral" to "MS", "perplexity" to "PX", "midjourney" to "MJ", "notebooklm" to "NL", "notebook" to "NB",
        "anthropic" to "AN", "metaai" to "MA", "suno" to "SN", "sora" to "SR",
        // Google и Microsoft
        "google" to "GO", "gmail" to "GML", "googlemaps" to "MP", "maps" to "MP", "googledrive" to "GD", "drive" to "GD", "googlephotos" to "PH",
        "outlook" to "OL", "teams" to "MT", "onedrive" to "OD", "chrome" to "CR", "edge" to "ED", "firefox" to "FF", "opera" to "OP",
        // игры, магазины, прочее
        "steam" to "ST", "epicgames" to "EG", "roblox" to "RB", "minecraft" to "MC", "battlenet" to "BN", "amazon" to "AZ", "aliexpress" to "AE",
        "github" to "GH", "wikipedia" to "WK", "dropbox" to "DB", "notion" to "NO", "figma" to "FG", "canva" to "CV", "obsidian" to "OB",
        "яндекс" to "YA", "yandex" to "YA", "сбер" to "SB", "sber" to "SB", "тинькофф" to "TB", "tinkoff" to "TB", "ozon" to "OZ", "озон" to "OZ",
    )

    private val VOWELS = "aeiouyаеёиоуыэюя"

    /** Первая буква и следующая согласная: Starlink → ST, Gmail → GM; нет согласной — вторая буква. */
    private fun firstAndConsonant(word: String): String {
        if (word.length <= 2) return word
        val rest = word.drop(1)
        val c = rest.firstOrNull { it.isLetter() && it.lowercaseChar() !in VOWELS } ?: rest[0]
        return word[0].toString() + c
    }

    private fun long(label: String): String {
        val clean = label.filter { it.isLetterOrDigit() }
        return clean.take(3).uppercase().ifEmpty { "?" }
    }

    fun codes(labels: List<String>): List<String> {
        val base = labels.map { of(it) }
        val step2 = base.mapIndexed { i, c -> if (base.count { it == c } > 1) long(labels[i]) else c }
        val seen = HashMap<String, Int>()
        return step2.mapIndexed { i, c ->
            if (step2.count { it == c } > 1) { val n = (seen[c] ?: 0) + 1; seen[c] = n; c + n } else c
        }
    }
}

/** Сколько колонок целей показывается на главном экране: два ряда по семь. Всё остальное — в окне «Сменить сервер». */
object TargetMatrixLimits {
    const val PER_ROW = 7
    const val MAX = 14
}
