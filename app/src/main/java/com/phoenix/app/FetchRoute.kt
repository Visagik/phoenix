package com.phoenix.app

/**
 * Путь загрузки источников в одном сборе: напрямую или через VPN решается по фактам этого сбора, а не по режиму и не по лимиту.
 * Один экземпляр на сбор, безопасен для параллельных загрузок.
 *
 * - DIRECT_FIRST (по умолчанию): сначала напрямую, при неудаче один повтор через VPN.
 * - VPN_FIRST: несколько источников подряд не открылись напрямую, но скачались через VPN, значит прямой путь в этой сети закрыт:
 *   следующие источники идут сразу через VPN (не ждут таймаут прямой попытки). Если через VPN не вышло, один раз пробуем и напрямую:
 *   отдельные зеркала могут открываться напрямую.
 * - DIRECT_ONLY: несколько повторов через VPN подряд провалились (код 0, ни одного байта) и контрольная проверка через VPN тоже не ответила,
 *   значит путь через туннель не работает: пока только напрямую. Плохие источники (код 0, но байты шли) путь через VPN убитым не делают.
 *   Это не окончательно: связь может вернуться посреди сбора, поэтому не раньше чем через [RECHECK_AFTER_MS] перед очередной загрузкой
 *   делается ещё одна контрольная проверка; ответила — загрузки возвращаются на VPN, а источники, которым за это время VPN не пробовали,
 *   получают одну повторную попытку через VPN ([takeRetries]).
 *
 * Успех на другом пути сбрасывает соответствующую серию.
 */
class FetchRoute(private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class Mode { DIRECT_FIRST, VPN_FIRST, DIRECT_ONLY }

    /** В каком порядке пробовать пути для следующего источника. */
    enum class Order { VPN_THEN_DIRECT, DIRECT_THEN_VPN, DIRECT_ONLY }

    private var rescuedRun = 0
    private var vpnFailRun = 0
    private var vpnFirstTried = 0
    private var vpnFirstOk = 0
    private var vpnAfterDirectTried = 0
    private var vpnAfterDirectOk = 0
    private var wentDirectOnly = false
    private var directOnlySince = 0L
    private var returnedToVpn = 0
    private val skippedVpn = LinkedHashSet<String>()
    private var aliveOrigin = ""
    private var controlPending = false
    private var controlsUsed = 0
    private var controlsAlive = 0
    private var controlsExhausted = false
    /** Пустых неудач подряд (код 0, без байт) уже после исчерпанного лимита проверок; [VPN_DEAD_AFTER] × 2 — путь считается умершим (Д3). */
    private var emptyAfterLimit = 0
    private var deadAfterLimit = false

    /** Подряд источников, которые не открылись напрямую, но скачались через VPN. */
    @Synchronized fun rescuedInRow(): Int = rescuedRun

    @Synchronized fun mode(): Mode = when {
        wentDirectOnly -> Mode.DIRECT_ONLY
        rescuedRun >= VPN_FIRST_AFTER -> Mode.VPN_FIRST
        else -> Mode.DIRECT_FIRST
    }

    /**
     * Порядок путей для источника. [vpnUsable]: VPN включён, есть сеть и для этого источника ещё не пробовали через VPN
     * (не больше одного повтора через VPN на источник за запрос).
     */
    fun order(vpnUsable: Boolean): Order {
        if (!vpnUsable) return Order.DIRECT_ONLY
        return when (mode()) {
            Mode.VPN_FIRST -> Order.VPN_THEN_DIRECT
            Mode.DIRECT_ONLY -> Order.DIRECT_ONLY
            Mode.DIRECT_FIRST -> Order.DIRECT_THEN_VPN
        }
    }

    /** Источник открылся напрямую с первой попытки (в режиме VPN_FIRST прямую попытку после провала VPN сюда не передают). */
    @Synchronized fun directOpened() { rescuedRun = 0 }

    /**
     * Итог попытки через VPN. [ok] — подписка скачана; [code] — код ответа через VPN (0 — соединения нет: обрыв, таймаут, исключение);
     * [bytes] — сколько байт пришло через туннель до ошибки; [origin] — «схема://узел/» источника (для контрольной проверки).
     * [afterDirectFail]: до неё напрямую не открылось (иначе её запустили сразу, режимом VPN_FIRST).
     * Любой HTTP-ответ (404, 403, 5xx, страница без конфигов) и любые пришедшие байты доказывают, что туннель работает, и обнуляют серию провалов:
     * плохой источник (медленный, обрывающийся, слишком большой) не должен считаться смертью пути через VPN. Серия растёт только при коде 0 без байт.
     * Серия «закрыт прямой путь» растёт только от успеха после провала напрямую.
     */
    @Synchronized fun vpnResult(ok: Boolean, code: Int, afterDirectFail: Boolean, bytes: Long = 0, origin: String = "") {
        if (afterDirectFail) vpnAfterDirectTried++ else vpnFirstTried++
        if (ok) { if (afterDirectFail) vpnAfterDirectOk++ else vpnFirstOk++ }
        if (code != 0 || bytes > 0) {
            vpnFailRun = 0
            if (origin.isNotBlank()) aliveOrigin = origin
        } else vpnFailRun++
        // Д3: проверки кончились, а последняя была «жив» — потом VPN мог умереть; новых проверок нет, поэтому судим по серии пустых неудач
        if (code != 0 || bytes > 0) emptyAfterLimit = 0
        else if (controlsUsed >= MAX_CONTROLS && !wentDirectOnly && ++emptyAfterLimit >= VPN_DEAD_AFTER * 2) {
            wentDirectOnly = true
            deadAfterLimit = true
            directOnlySince = clock()
        }
        if (ok && afterDirectFail) rescuedRun++
    }

    /**
     * Нужна ли контрольная проверка VPN. Два случая:
     * 1) [VPN_DEAD_AFTER] неудач подряд (код 0, без байт) — прежде чем отказаться от VPN в этом сборе;
     * 2) уже DIRECT_ONLY и с момента перехода (или прошлой проверки) прошло не меньше [RECHECK_AFTER_MS] — вдруг связь вернулась.
     * Возвращает адрес для короткого запроса через VPN: узел, уже ответивший через VPN в этом сборе, иначе [fallback] (адрес проверки связи).
     * null — проверка не нужна, уже идёт или лимит [MAX_CONTROLS] за сбор исчерпан (в случае 1 серия сбрасывается, режим не меняется:
     * VPN уже был подтверждён живым; в случае 2 остаётся DIRECT_ONLY). Одна проверка на серию неудач; результат нужно передать в [controlResult].
     */
    @Synchronized fun takeControl(fallback: String): String? {
        if (controlPending) return null
        if (wentDirectOnly) {
            if (controlsUsed >= MAX_CONTROLS || clock() - directOnlySince < RECHECK_AFTER_MS) return null
        } else {
            if (vpnFailRun < VPN_DEAD_AFTER) return null
            if (controlsUsed >= MAX_CONTROLS) {
                vpnFailRun = 0
                controlsExhausted = true
                return null
            }
        }
        controlsUsed++
        controlPending = true
        return aliveOrigin.ifBlank { fallback }
    }

    /** Что показала контрольная проверка (для строки журнала). */
    enum class Control { ALIVE, WENT_DIRECT_ONLY, RETURNED, STILL_DIRECT_ONLY }

    /**
     * Итог контрольной проверки. Ответил: серия сброшена, режим не меняется; если был DIRECT_ONLY — путь через VPN возвращён ([Control.RETURNED]).
     * Не ответил: дальше только напрямую, следующая проверка не раньше чем через [RECHECK_AFTER_MS].
     */
    @Synchronized fun controlResult(alive: Boolean): Control {
        controlPending = false
        return if (alive) {
            controlsAlive++
            vpnFailRun = 0
            if (wentDirectOnly) { wentDirectOnly = false; returnedToVpn++; Control.RETURNED } else Control.ALIVE
        } else {
            val was = wentDirectOnly
            wentDirectOnly = true
            directOnlySince = clock()
            if (was) Control.STILL_DIRECT_ONLY else Control.WENT_DIRECT_ONLY
        }
    }

    /** Источник получил код 0 без попытки через VPN, потому что путь через VPN был закрыт (DIRECT_ONLY). */
    @Synchronized fun noteSkippedVpn(url: String) { if (wentDirectOnly) skippedVpn += url }

    /** Через VPN источник всё-таки пробовали: повторять его не нужно. */
    @Synchronized fun noteVpnTried(url: String) { skippedVpn -= url }

    @Synchronized fun hasRetries(): Boolean = skippedVpn.isNotEmpty()

    /**
     * Источники для одной повторной попытки через VPN: только те, что за время DIRECT_ONLY не пробовались через VPN.
     * Пока путь через VPN закрыт, пусто (список сохраняется до возвращения). Список выдаётся один раз.
     */
    @Synchronized fun takeRetries(): List<String> {
        if (wentDirectOnly || skippedVpn.isEmpty()) return emptyList()
        return skippedVpn.toList().also { skippedVpn.clear() }
    }

    /** Почему повтор через VPN не делали: правда, а не «VPN выключен» по умолчанию. */
    fun noVpnReason(vpnOn: Boolean, offline: Boolean): String? = when {
        !vpnOn -> REASON_VPN_OFF
        offline -> REASON_OFFLINE
        mode() == Mode.DIRECT_ONLY -> REASON_VPN_DEAD
        else -> null
    }

    /** Строка для журнала в конце сбора; null, если через VPN не ходили. */
    @Synchronized fun summary(): String? {
        if (vpnFirstTried == 0 && vpnAfterDirectTried == 0 && !wentDirectOnly) return null
        return "FETCH: через VPN сразу: $vpnFirstTried попыток, скачано $vpnFirstOk; после прямой попытки: $vpnAfterDirectTried попыток, скачано $vpnAfterDirectOk" +
            (if (controlsUsed > 0) "; контрольных проверок VPN: $controlsUsed, ответили $controlsAlive" else "") +
            (if (controlsExhausted) " (лимит $MAX_CONTROLS исчерпан, режим не менял)" else "") +
            (if (returnedToVpn > 0) "; возвращался к VPN: $returnedToVpn" else "") +
            (if (deadAfterLimit) "; после исчерпанного лимита проверок $emptyAfterLimit неудач подряд без ответа и без байт — дальше только напрямую" else "") +
            (if (wentDirectOnly) "; путь через VPN в этом сборе перестал отвечать — дальше только напрямую" else "")
    }

    companion object {
        /** Сколько источников подряд должны не открыться напрямую и скачаться через VPN, чтобы идти сразу через VPN. */
        const val VPN_FIRST_AFTER = 3

        /** Сколько повторов через VPN подряд (код 0, без байт) должны провалиться, чтобы сделать контрольную проверку туннеля. */
        const val VPN_DEAD_AFTER = 3

        /** Сколько контрольных проверок VPN можно сделать за один сбор. */
        const val MAX_CONTROLS = 5

        /**
         * Через сколько после перехода в DIRECT_ONLY (или прошлой проверки) пробовать вернуть VPN. Бюджет: контрольный запрос ≤ 2 × 10 с
         * (соединение + чтение), 5 проверок ≤ 100 с одного потока из 3, а сбор вправе идти 5 минут: на скачивание остаётся минимум 200 с.
         */
        const val RECHECK_AFTER_MS = 15_000L

        const val REASON_VPN_OFF = "VPN выключен"
        const val REASON_OFFLINE = "нет сети"
        const val REASON_VPN_DEAD = "путь через VPN в этом сборе не отвечает"
    }
}

/** Считает байты, прошедшие через поток: при обрыве видно, сколько успело прийти. */
internal class CountingInput(src: java.io.InputStream) : java.io.FilterInputStream(src) {
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}

/** Причина кода 0 для журнала: класс ошибки, время до неё, сколько байт успело прийти. Адреса и тексты ошибок в журнал не попадают. */
object FetchFailure {
    enum class Kind(val text: String) {
        READ_TIMEOUT("таймаут чтения"),
        CONNECT_TIMEOUT("таймаут соединения"),
        RESET("сброс соединения"),
        REFUSED("отказ в соединении"),
        UNREACHABLE("сеть недоступна"),
        DNS("не найден адрес узла"),
        TLS("ошибка TLS"),
        OTHER("ошибка"),
    }

    /**
     * [elapsedMs] и [timeoutMs] (если известны): ConnectException без внятной причины в тексте, дожидавшийся почти всего таймаута соединения,
     * это таймаут соединения. Android пишет «after 30000ms» в любую ошибку соединения (это таймаут, а не прошедшее время), поэтому по тексту не судим.
     */
    fun classify(e: Throwable, elapsedMs: Long = -1, timeoutMs: Long = 0): Kind {
        val msg = (e.message ?: "").lowercase()
        return when {
            e is java.net.SocketTimeoutException -> if (msg.contains("connect")) Kind.CONNECT_TIMEOUT else Kind.READ_TIMEOUT
            e is java.net.UnknownHostException -> Kind.DNS
            e is javax.net.ssl.SSLException && !(msg.contains("reset") || msg.contains("closed") || msg.contains("abort")) -> Kind.TLS
            msg.contains("refused") -> Kind.REFUSED
            msg.contains("unreachable") -> Kind.UNREACHABLE
            msg.contains("timed out") || msg.contains("etimedout") -> Kind.CONNECT_TIMEOUT
            e is java.net.ConnectException && timeoutMs > 0 && elapsedMs >= timeoutMs * 9 / 10 -> Kind.CONNECT_TIMEOUT
            e is java.io.EOFException || msg.contains("reset") || msg.contains("broken pipe") || msg.contains("abort") ||
                msg.contains("unexpected end") || msg.contains("closed") -> Kind.RESET
            else -> Kind.OTHER
        }
    }

    fun size(bytes: Long): String = if (bytes < 1024) "$bytes Б" else "${bytes / 1024} КБ"

    /**
     * «таймаут чтения после 30000 мс, получено 812 КБ». [afterLimit] — к моменту ошибки общий лимит сбора уже вышел.
     * Для прочих ошибок вместо текста ошибки только имя класса исключения.
     */
    fun describe(e: Throwable, elapsedMs: Long, bytes: Long, afterLimit: Boolean = false, timeoutMs: Long = 0): String {
        val kind = classify(e, elapsedMs, timeoutMs)
        val what = (if (kind == Kind.OTHER) "ошибка ${e.javaClass.simpleName}" else kind.text) +
            (if (kind == Kind.TLS) TlsRoots.issuerNote(e)?.let { " (нет доверия к издателю: $it)" } ?: "" else "")
        return "$what после $elapsedMs мс, получено ${size(bytes)}" + (if (afterLimit) ", общий лимит сбора уже исчерпан" else "")
    }

    /** Без исключения (переход на запрещённый адрес и подобное): причина словами. */
    fun describe(reason: String, elapsedMs: Long, bytes: Long): String = "$reason после $elapsedMs мс, получено ${size(bytes)}"
}
