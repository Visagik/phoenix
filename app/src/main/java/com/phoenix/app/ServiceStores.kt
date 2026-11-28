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
                    if (sid in r.vetoed) continue // пометка «Не работает» поставлена пользователем, это не замер (О3)
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
