package com.phoenix.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Диагностика проб через ядро за один проход перепроверки (этап 0 аудита проверки серверов). Поведение не меняет, только считает и
 * отдаёт пять строк для журнала `RECHECK: …`: по ним решается, нужны ли «без запасного адреса после таймаута» (С1), «раннее освобождение места» (С2)
 * и поправка на нагрузку (Т4). Чистая логика, потокобезопасна: проб идёт до 32 одновременно.
 * Считает только между [begin] и [end]: другие пробы (службы, цели) в итог не попадают.
 */
object ProbeStats {
    enum class Err(val label: String) {
        TIMEOUT("таймаут"), EOF("обрыв/EOF"), REFUSED("отказ"), TLS("TLS"), STATUS("код ответа"), CLOSED_PIPE("closed pipe"), OTHER("прочее")
    }

    private const val MAX_SAMPLES = 20_000
    private const val MAX_EXAMPLES = 8
    private const val EXAMPLE_CHARS = 120

    @Volatile private var active = false
    private val errs = Array(Err.entries.size) { AtomicInteger() }
    private val examples = ConcurrentHashMap<String, Boolean>()
    private val deadBuckets = Array(4) { AtomicInteger() } // до 3 с, 3–8 с, 8–13 с, дольше 13 с
    private val liveDelay = ArrayList<Long>()
    private val liveWall = ArrayList<Long>()
    private val altTried = AtomicInteger()
    private val altSaved = AtomicInteger()
    private val altSavedAfterTimeout = AtomicInteger()
    private val deadCount = AtomicInteger()
    private val liveCount = AtomicInteger()
    private val altTimeoutTried = AtomicInteger()

    /** Текст ошибки Go → вид. Порядок проверок важен: «closed pipe» раньше «EOF», «таймаут» раньше «обрыва». */
    fun classify(text: String): Err {
        val t = text.lowercase()
        return when {
            t.contains("closed pipe") || t.contains("closed network") -> Err.CLOSED_PIPE
            Baseline.answered(text) -> Err.STATUS
            t.contains("timeout") || t.contains("deadline exceeded") || t.contains("timed out") -> Err.TIMEOUT
            t.contains("refused") -> Err.REFUSED
            t.contains("tls:") || t.contains("x509") || t.contains("certificate") || t.contains("handshake") -> Err.TLS
            t.contains("eof") || t.contains("reset by peer") || t.contains("broken pipe") || t.contains("connection reset") -> Err.EOF
            else -> Err.OTHER
        }
    }

    fun begin() {
        active = false
        errs.forEach { it.set(0) }
        examples.clear()
        deadBuckets.forEach { it.set(0) }
        synchronized(liveDelay) { liveDelay.clear() }
        synchronized(liveWall) { liveWall.clear() }
        altTried.set(0); altSaved.set(0); altSavedAfterTimeout.set(0); altTimeoutTried.set(0); deadCount.set(0); liveCount.set(0)
        active = true
    }

    fun end() { active = false }

    /**
     * Итог одной пробы адреса проверки.
     * @param delayMs задержка по ядру (≥ 0 — живой) или отрицательное число
     * @param wallMs время всей пробы в Kotlin (с запуском ядра и повторами)
     * @param error последняя ошибка ядра (для мёртвого)
     */
    fun record(delayMs: Long, wallMs: Long, error: String) {
        if (!active) return
        if (delayMs >= 0) {
            liveCount.incrementAndGet()
            synchronized(liveDelay) { if (liveDelay.size < MAX_SAMPLES) liveDelay += delayMs }
            synchronized(liveWall) { if (liveWall.size < MAX_SAMPLES) liveWall += wallMs }
            return
        }
        deadCount.incrementAndGet()
        deadBuckets[when { wallMs < 3_000 -> 0; wallMs < 8_000 -> 1; wallMs < 13_000 -> 2; else -> 3 }].incrementAndGet()
        errs[classify(error).ordinal].incrementAndGet()
        if (error.isNotBlank() && examples.size < MAX_EXAMPLES) examples.putIfAbsent(sanitize(error), true)
    }

    /** Запасной адрес: попытка ([afterTimeout] — первая ошибка была таймаутом) и спас ли он (ответил). */
    fun alternative(afterTimeout: Boolean, saved: Boolean) {
        if (!active) return
        altTried.incrementAndGet()
        if (afterTimeout) altTimeoutTried.incrementAndGet()
        if (saved) { altSaved.incrementAndGet(); if (afterTimeout) altSavedAfterTimeout.incrementAndGet() }
    }

    /** Текст ошибки без адресов и ключей: ссылки (через LogMask), IP, имена узлов (кроме адресов проверки), длинные токены; до 120 символов. */
    fun sanitize(raw: String): String {
        var s = LogMask.apply(raw.replace('\n', ' ').trim())
        s = Regex("""\[?(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}]?(?::\d+)?""").replace(s, "<ip>")
        s = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""").replace(s, "<ip>")
        s = Regex("""\b[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+\b""").replace(s) { m ->
            val v = m.value.lowercase()
            if (v == "gstatic.com" || v.endsWith(".gstatic.com") || v == "cloudflare.com" || v.endsWith(".cloudflare.com") || v == "google.com" || v.endsWith(".google.com")) m.value else "<host>"
        }
        s = Regex("""[A-Za-z0-9_\-=]{20,}""").replace(s, "<token>")
        return s.take(EXAMPLE_CHARS)
    }

    /** Ближайший ранг: p в [0, 1]; пусто — null. */
    fun percentile(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val idx = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[idx]
    }

    /** Пять строк для журнала (без префикса «RECHECK: »), либо пусто, если проб не было. */
    fun lines(parallel: Int, net: String): List<String> {
        if (deadCount.get() == 0 && liveCount.get() == 0) return emptyList()
        val out = ArrayList<String>()
        out += "ошибки ядра — " + Err.entries.joinToString(", ") { "${it.label} ${errs[it.ordinal].get()}" }
        out += "примеры ошибок — " + (if (examples.isEmpty()) "нет" else examples.keys.sorted().joinToString(" | "))
        out += "мёртвые по времени — до 3 с ${deadBuckets[0].get()}, 3–8 с ${deadBuckets[1].get()}, 8–13 с ${deadBuckets[2].get()}, дольше 13 с ${deadBuckets[3].get()}"
        val d = synchronized(liveDelay) { liveDelay.toList() }
        val w = synchronized(liveWall) { liveWall.toList() }
        fun f(v: Long?) = v?.toString() ?: "—"
        out += "живые — задержка p50 ${f(percentile(d, 0.5))}, p90 ${f(percentile(d, 0.9))}, макс ${f(d.maxOrNull())} мс (по ядру); " +
            "время пробы p50 ${f(percentile(w, 0.5))}, p90 ${f(percentile(w, 0.9))} мс; параллельно проб $parallel, сеть $net"
        out += "запасной адрес — попыток ${altTried.get()}, спас ${altSaved.get()} (из них после таймаута ${altSavedAfterTimeout.get()} из ${altTimeoutTried.get()} попыток после таймаута)"
        return out
    }
}
