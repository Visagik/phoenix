package com.phoenix.app

import java.io.File

/**
 * Частые смены сети (Wi-Fi ↔ мобильная, журнал 06.11: 32 смены и 24 смены режима за 11 часов).
 * Если сеть меняется ≥ [LIMIT] раз за [WINDOW_MS], одного определения типа блокировок мало: режим подтверждается дольше,
 * иначе каждая смена запускает дорогое переключение режима и перебор серверов.
 */
class FlapGuard(private val windowMs: Long = WINDOW_MS, private val limit: Int = LIMIT) {
    private val times = ArrayList<Long>()

    @Synchronized fun note(now: Long) {
        trim(now)
        times.add(now)
    }

    @Synchronized fun count(now: Long): Int {
        trim(now)
        return times.size
    }

    fun flapping(now: Long): Boolean = count(now) >= limit

    private fun trim(now: Long) {
        times.removeAll { now - it > windowMs || now < it }
    }

    companion object {
        const val WINDOW_MS = 180_000L
        const val LIMIT = 2
    }
}

/** Последний сервер, который реально работал в сети [net] в режиме [mode]; при смене режима пробуется первым. */
class LastGoodStore(private val file: File) {
    private data class Row(val net: String, val mode: String, val link: String, val at: Long)

    private fun load(): MutableList<Row> {
        if (!file.exists()) return ArrayList()
        val out = ArrayList<Row>()
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size < 4) continue
            val at = p[3].toLongOrNull() ?: continue
            out += Row(p[0], p[1], p[2], at)
        }
        return out
    }

    @Synchronized fun remember(net: String, mode: String, link: String, now: Long) {
        if (net.isBlank() || link.isBlank() || net.contains('\t') || link.contains('\t')) return
        val rows = load()
        rows.removeAll { it.net == net && it.mode == mode }
        rows += Row(net, mode, link, now)
        val keep = rows.sortedByDescending { it.at }.take(MAX_ROWS)
        file.writeText(keep.joinToString("\n") { "${it.net}\t${it.mode}\t${it.link}\t${it.at}" } + "\n")
    }

    @Synchronized fun get(net: String, mode: String, now: Long, maxAgeMs: Long = MAX_AGE_MS): String? =
        load().firstOrNull { it.net == net && it.mode == mode && now - it.at in 0..maxAgeMs }?.link

    companion object {
        const val MAX_ROWS = 64
        const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
        private val cache = java.util.concurrent.ConcurrentHashMap<String, LastGoodStore>()
        fun of(file: File): LastGoodStore = cache.getOrPut(file.absolutePath) { LastGoodStore(file) }
    }
}

/** Сколько времени за день не было связи из-за смены режима и смены сервера (для оценки, где теряются минуты). */
class DowntimeStats(private val file: File) {
    private data class Row(val day: String, val kind: String, val count: Int, val total: Int, val max: Int)

    private fun load(): MutableList<Row> {
        if (!file.exists()) return ArrayList()
        val out = ArrayList<Row>()
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size < 5) continue
            out += Row(p[0], p[1], p[2].toIntOrNull() ?: continue, p[3].toIntOrNull() ?: continue, p[4].toIntOrNull() ?: continue)
        }
        return out
    }

    /** @return false, если перерыв слишком длинный, чтобы его считать (скорее всего, интернета не было вообще) */
    @Synchronized fun add(kind: String, sec: Int, day: String): Boolean {
        if (sec < 0 || sec > MAX_SEC) return false
        val rows = load()
        val i = rows.indexOfFirst { it.day == day && it.kind == kind }
        if (i >= 0) {
            val r = rows[i]
            rows[i] = r.copy(count = r.count + 1, total = r.total + sec, max = maxOf(r.max, sec))
        } else rows += Row(day, kind, 1, sec, sec)
        val days = rows.map { it.day }.distinct().sortedDescending().take(KEEP_DAYS).toSet()
        val keep = rows.filter { it.day in days }
        file.writeText(keep.joinToString("\n") { "${it.day}\t${it.kind}\t${it.count}\t${it.total}\t${it.max}" } + "\n")
        return true
    }

    @Synchronized fun summary(day: String): String {
        val rows = load().filter { it.day == day }
        if (rows.isEmpty()) return "перерывов нет"
        val n = rows.sumOf { it.count }
        val total = rows.sumOf { it.total }
        val max = rows.maxOf { it.max }
        val parts = rows.joinToString(", ") { "${kindTitle(it.kind)} ${it.count}" }
        return "перерывов $n, всего ${total / 60} мин ${total % 60} с, самый долгий $max с ($parts)"
    }

    companion object {
        const val MAX_SEC = 1_800
        const val KEEP_DAYS = 14
        const val MODE = "MODE"
        const val FAILOVER = "FAILOVER"
        /** Связь пропала сама (поиск не нашёл другой сервер), потом вернулась на прежнем. */
        const val LOSS = "LOSS"
        fun kindTitle(kind: String) = when (kind) { MODE -> "смена режима"; FAILOVER -> "смена сервера"; LOSS -> "сбой связи"; else -> kind }
        private val cache = java.util.concurrent.ConcurrentHashMap<String, DowntimeStats>()
        fun of(file: File): DowntimeStats = cache.getOrPut(file.absolutePath) { DowntimeStats(file) }
    }
}

/**
 * Общая отметка «сеть сейчас часто меняется»: пока она стоит, тяжёлые фоновые задачи (полная перепроверка, проверка целей) откладываются,
 * чтобы не гонять проверки по сети, которая через минуту станет другой (журнал 06.11: 24 смены режима за 11 часов).
 */
object NetFlap {
    const val DEFER_MS = 120_000L
    @Volatile private var until = 0L
    fun mark(now: Long) { until = now + DEFER_MS }
    fun busy(now: Long): Boolean = now < until && until - now <= DEFER_MS
    fun clear() { until = 0L }
}

/**
 * Не повторять в журнале одинаковую строку чаще, чем раз в [quietMs]: в журнале 07.11 одна и та же строка DETECT («чёрные списки · 10 из 11…»)
 * повторялась 1322 раза и занимала 13 % файла, из-за чего пять мегабайт хватало меньше чем на сутки.
 */
class RepeatMute(private val quietMs: Long = 30 * 60_000L) {
    private data class Last(val text: String, val at: Long)
    private val last = HashMap<String, Last>()

    @Synchronized fun shouldLog(key: String, text: String, now: Long): Boolean {
        val l = last[key]
        if (l != null && l.text == text && now >= l.at && now - l.at < quietMs) return false
        last[key] = Last(text, now)
        return true
    }
}

/**
 * Подмешивает «чужие» серверы (белых списков при поиске в чёрных) в очередь, а не оставляет их в самом конце.
 * Журнал 07.11: в чёрных списках очередь из ~630 своих серверов никогда не доходила до белых, а Gemini и NotebookLM закрыты на «обычных» выходах.
 * После каждых [every] своих идёт один чужой, пока чужие не кончатся.
 */
/**
 * Поиск сервера — гонка: проверяются все кандидаты сразу, и побеждают те, кто ответил первым, а не те, кто стоит выше в очереди
 * (журнал 20.11: для Notebook в очереди были серверы с ✓, а выбралась непроверенная Финляндия из белых списков — она просто ответила быстрее).
 * Поэтому серверы, где нужный сервис уже открывался, проверяются отдельным первым этапом.
 */
object ReadyFirst {
    /** Серверы очереди, подходящие открытому приложению; пусто, если такого этапа не нужно (нет приложения, подходят все или никто). */
    /** Первый этап — десятки серверов, а не почти вся очередь (журнал 20.11: «есть 1521 серверов» из 1677 → до 180 с вместо 120). */
    const val STAGE_LIMIT = 40

    fun pick(ordered: List<Candidate>, fit: Set<String>?, limit: Int = STAGE_LIMIT): List<Candidate> {
        if (fit.isNullOrEmpty()) return emptyList()
        val ready = ordered.filter { it.identity in fit }
        return if (ready.isEmpty() || ready.size >= ordered.size) emptyList() else ready.take(limit)
    }
}

object QueueMix {
    fun <T> mix(own: List<T>, foreign: List<T>, every: Int = 2): List<T> {
        if (foreign.isEmpty()) return own
        val out = ArrayList<T>(own.size + foreign.size)
        var f = 0
        var sinceF = 0
        for (o in own) {
            if (sinceF >= every && f < foreign.size) { out += foreign[f++]; sinceF = 0 }
            out += o
            sinceF++
        }
        while (f < foreign.size) out += foreign[f++]
        return out
    }
}

/**
 * Первый запуск без истории (нет бэкапа, ни одного успешного сбора): всё надо проверять с нуля, десятки тысяч
 * конфигов. Чтобы телефон не захлёбывался (приложение «не отвечает»), первый сбор откладывается на минуту-полторы,
 * а первая перепроверка идёт втрое меньшим числом параллельных проверок.
 */
object ColdStart {
    const val FIRST_DELAY_SEC = 90L

    fun isFirstRun(lastSuccessAt: Long): Boolean = lastSuccessAt <= 0L

    /** Сколько проверок через ядро одновременно при перепроверке; [base] — обычное число для телефона. */
    fun recheckParallel(base: Int, lastSuccessAt: Long): Int =
        if (isFirstRun(lastSuccessAt)) (base / 3).coerceAtLeast(2) else base

    /** Задержка запуска «догона» при старте процесса; ручной запуск и будильник не откладываются. */
    fun catchUpDelaySec(trigger: String, lastSuccessAt: Long): Long =
        if (trigger == "catch-up" && isFirstRun(lastSuccessAt)) FIRST_DELAY_SEC else 0L
}

/**
 * С какого момента считать перерыв связи. Раньше счёт шёл от начала смены сервера/режима, а связь пропадала раньше:
 * 17 с до смены сервера (08:20:57 → 08:21:14) и около 47 с при смене режима (сбой 07:06:27, смена 07:07:14; счётчик показал 43 с вместо ~92).
 * Берём время первой неудачи проверки связи, если она была недавно и не относится к давнему эпизоду.
 */
object OutageStart {
    const val WINDOW_MS = 3 * 60_000L
    fun pick(now: Long, failSince: Long): Long = if (failSince > 0L && now - failSince in 0..WINDOW_MS) failSince else now
}

/**
 * Защита от «шторма смен»: если автосмены идут одна за другой (в журнале ~30 в сутки, пачками), следующую откладываем —
 * часто виновата сеть, а не серверы, и каждая смена рвёт связь.
 */
object SwitchStorm {
    const val WINDOW_MS = 10 * 60_000L
    const val FREE_SWITCHES = 3
    const val HOLD_STEP_MS = 20_000L
    const val HOLD_MAX_MS = 90_000L

    /** Пауза до следующей автосмены по времени прошлых смен (включая только что сделанную). */
    fun holdMs(switchTimes: List<Long>, now: Long): Long {
        val n = switchTimes.count { now - it in 0..WINDOW_MS }
        if (n <= FREE_SWITCHES) return 0L
        return ((n - FREE_SWITCHES) * HOLD_STEP_MS).coerceAtMost(HOLD_MAX_MS)
    }
}

/**
 * Ошибка сертификата — это «сервер до сайта дотянулся, но сертификат не принят» (банки, Microsoft и подобные меняют/проверяют сертификаты),
 * а не «сайт закрыт». Обрыв рукопожатия без сертификата сюда не входит.
 */
object TlsOutcome {
    fun certProblem(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth < 8) {
            if (t is java.security.cert.CertificateException || t is javax.net.ssl.SSLPeerUnverifiedException || t is java.security.cert.CertPathValidatorException) return true
            if (t.javaClass.name.endsWith("CertificateException") || t.javaClass.name.endsWith("CertPathValidatorException")) return true
            t = t.cause
            depth++
        }
        return false
    }
}

/**
 * Решение «2 из 3» для проверки страницы сайта по региону: страница через один и тот же сервер отвечает по-разному от запроса к запросу
 * (журнал 21.11: NotebookLM и Gemini через GB то «✓», то «✗»). Нужны два одинаковых ответа; третье чтение только при расхождении.
 * @param read true — «не поддерживается в стране», false — обычная страница, null — не удалось узнать (голосом не считается)
 * @return решение или null, если двух одинаковых ответов не набралось
 */
object RegionVote {
    fun decide(maxReads: Int, read: () -> Boolean?): Boolean? {
        var yes = 0
        var no = 0
        var reads = 0
        while (reads < maxReads) {
            when (read()) {
                true -> yes++
                false -> no++
                null -> {}
            }
            reads++
            if (yes >= 2) return true
            if (no >= 2) return false
        }
        return if (maxReads == 1) (if (yes == 1) true else if (no == 1) false else null) else null
    }
}


/**
 * Когда сбой проверки связи считать виной сервера. Журналы 20–21.11: около 30 % падений восстанавливались сами, а смена сервера
 * шла через секунды после предупреждения системы «сеть пропадёт» (простой 50 с).
 */
object HealthGate {
    /** После смены сети или предупреждения системы сбой связи не вешаем на сервер и не перебираем. */
    const val SETTLE_MS = 60_000L
    /** «Недавно» для определения типа блокировок: раньше второй неудачи оно нужно только сразу после смены сети. */
    const val RECENT_MS = 120_000L
    /** Сервер, упавший раньше этого после выбора, получает долгий карантин. */
    const val EARLY_FALL_MS = 120_000L

    fun settling(now: Long, shakeAt: Long): Boolean = shakeAt > 0L && now - shakeAt in 0 until SETTLE_MS
    fun recent(now: Long, shakeAt: Long): Boolean = shakeAt > 0L && now - shakeAt in 0 until RECENT_MS
    /** Сколько неудач подряд нужно: на мобильной сети провалы короче, ждём третью. */
    fun failuresNeeded(mobile: Boolean): Int = if (mobile) 3 else 2
    /** На какой неудаче запускать определение режима (15 прямых запросов): сразу после смены сети — на первой, иначе на второй. */
    fun detectAt(netRecent: Boolean): Int = if (netRecent) 1 else 2
    fun earlyFall(now: Long, chosenAt: Long): Boolean = chosenAt > 0L && now - chosenAt in 0 until EARLY_FALL_MS
}


/**
 * Общий потолок времени одной смены сервера. Этапы (сначала подходящие под приложение, весь список, серверы из карантина) раньше
 * имели каждый свой бюджет и в сумме давали до 220 с поиска, а при неудачах с растущими паузами — простои по 187 и 506 с (журнал Samsung 21.11).
 */
object FailoverBudget {
    const val MOBILE_MS = 60_000L
    const val WIFI_MS = 120_000L
    const val FORCED_MS = 150_000L
    /** Этап короче этого не запускаем: поиск не успеет ничего проверить. */
    const val MIN_STAGE_MS = 5_000L

    fun totalMs(forced: Boolean, mobile: Boolean): Long = if (forced) FORCED_MS else if (mobile) MOBILE_MS else WIFI_MS

    /** Бюджет этапа: желаемый, но не больше оставшегося общего; 0 — этап пропускаем. */
    fun stageMs(now: Long, started: Long, totalMs: Long, wantMs: Long): Long {
        val left = totalMs - (now - started)
        val ms = minOf(wantMs, left)
        return if (ms < MIN_STAGE_MS) 0L else ms
    }
}


/**
 * Контрольный адрес для проверки списка обхода. В проход по «пора проверить» попадают в основном закрытые напрямую сервисы
 * (доверенные пропускаются), поэтому «не открылось ничего» было обычным итогом и принималось за отсутствие сети: на Xiaomi 44 раза за сутки,
 * с повторами через 8 и 20 с на каждую смену сети. Теперь отсутствие сети подтверждает не открывшийся и доверенный сервис.
 */
object DirectControl {
    /** До [n] доверенных сервисов, которых нет в проверяемых сейчас. */
    fun pick(allIds: List<String>, dueIds: Set<String>, trustedIds: Set<String>, n: Int = 2): List<String> =
        allIds.filter { it in trustedIds && it !in dueIds }.take(n)

    /** true — сети нет: не открылось ничего и контрольных адресов нет или они тоже не открылись. */
    fun noNetwork(anyOpened: Boolean, controlsTried: Boolean, controlOpened: Boolean): Boolean =
        !anyOpened && !(controlsTried && controlOpened)
}


/**
 * Параллельность проверок в зависимости от занятой памяти процесса. Журнал Samsung 21.11: при перепроверке вместе с поиском PSS доходил до 332 МБ,
 * а в 12:22 процесс убила система («не хватило памяти», 243 МБ). Каждая проба временным ядром — отдельный экземпляр Go.
 */
object MemGuard {
    const val HIGH_MB = 200
    const val CRITICAL_MB = 260

    /** Сколько проверок одновременно можно держать при [pssMb] занятой памяти; [base] — обычное значение. */
    fun scale(base: Int, pssMb: Int): Int = when {
        pssMb <= 0 -> base // не удалось измерить
        pssMb >= CRITICAL_MB -> maxOf(1, base / 4)
        pssMb >= HIGH_MB -> maxOf(2, base / 2).coerceAtMost(base)
        else -> base
    }
}
