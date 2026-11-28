package com.phoenix.app

/**
 * Сборка Phoenix Lite для слабого планшета (Acer B3-A32, Android 6, 2 ГБ). Все числа и флаги Lite живут только здесь; в остальном коде —
 * вызовы этих функций. Вариант задаётся при сборке (`BuildConfig.LITE`, productFlavor lite). Каждая функция берёт `lite` параметром
 * (по умолчанию [ON]): при `lite = false` результат равен входу, поведение телефона не меняется. Чистая логика; Android-часть — в [LiteEnv].
 */
object LiteProfile {
    val ON: Boolean = BuildConfig.LITE

    // ---- лимиты (стартовые, подстраиваются по журналу планшета) ----
    const val PROBE_PARALLEL = 2
    const val TCP_PARALLEL = 8
    const val RECHECK_MAX = 200
    const val CONFIGS_PER_SOURCE = 300
    const val CONFIGS_TOTAL = 800
    const val MAX_BODY_CHARS = 600_000
    const val MEM_HIGH_MB = 120
    const val MEM_CRITICAL_MB = 170

    fun probeCap(base: Int, lite: Boolean = ON): Int = if (lite) minOf(base, PROBE_PARALLEL) else base

    fun tcpCap(base: Int, lite: Boolean = ON): Int = if (lite) minOf(base, TCP_PARALLEL) else base

    fun bodyChars(base: Int, lite: Boolean = ON): Int = if (lite) minOf(base, MAX_BODY_CHARS) else base

    fun memHigh(base: Int, lite: Boolean = ON): Int = if (lite) minOf(base, MEM_HIGH_MB) else base

    fun memCritical(base: Int, lite: Boolean = ON): Int = if (lite) minOf(base, MEM_CRITICAL_MB) else base

    /** Для перепроверки: первые [RECHECK_MAX] в порядке рейтинга (список уже отсортирован), порядок сохраняется. */
    fun <T> capRecheck(ranked: List<T>, lite: Boolean = ON): List<T> = if (lite && ranked.size > RECHECK_MAX) ranked.subList(0, RECHECK_MAX).toList() else ranked

    /** После разбора одного источника: остаётся всё до [CONFIGS_PER_SOURCE]-й строки со ссылкой включительно; порядок сохраняется. */
    fun capSource(lines: List<String>, lite: Boolean = ON): List<String> {
        if (!lite) return lines
        var links = 0
        for ((i, l) in lines.withIndex()) {
            if (l.contains("://") && ++links > CONFIGS_PER_SOURCE) return lines.subList(0, i).toList()
        }
        return lines
    }

    /** Сколько строк со ссылками в источнике (для строки журнала «взял 300 из 1240»). */
    fun linkCount(lines: List<String>): Int = lines.count { it.contains("://") }

    /** Общий предел после Dedup.topPerAddress: первые [CONFIGS_TOTAL]. */
    fun <T> capTotal(items: List<T>, lite: Boolean = ON): List<T> = if (lite && items.size > CONFIGS_TOTAL) items.subList(0, CONFIGS_TOTAL).toList() else items

    /** Порядок важности при отрезке: 0 текущий сервер, 1 живой по последней проверке, 2 источник с высоким приоритетом, 3 обычный, 4 низкий/запасной. */
    fun keepRank(isCurrent: Boolean, aliveByCheck: Boolean, sourcePriority: Int): Int = when {
        isCurrent -> 0
        aliveByCheck -> 1
        sourcePriority > SourcePriority.NORMAL -> 2
        sourcePriority < SourcePriority.NORMAL -> 4
        else -> 3
    }

    /**
     * Общий предел с учётом важности: устойчивая сортировка по [rank] (меньше — выше), остаются первые [CONFIGS_TOTAL],
     * но список возвращается в ИСХОДНОМ порядке (дальше сбор полагается на «свежие первыми»). При `lite = false` вход как есть.
     */
    fun <T> capTotalRanked(items: List<T>, rank: (T) -> Int, lite: Boolean = ON): List<T> {
        if (!lite || items.size <= CONFIGS_TOTAL) return items
        val keep = items.indices.sortedBy { rank(items[it]) }.take(CONFIGS_TOTAL).toHashSet() // sortedBy устойчива
        return items.filterIndexed { i, _ -> i in keep }
    }

    // ---- принудительно выключено (4.1): геттеры настроек возвращают это значение, сохранённое в файле не трогаем (копия с планшета не ломает телефон) ----
    fun mode(saved: Mode, lite: Boolean = ON): Mode = if (lite) Mode.BLACKLIST else saved

    fun autoMode(saved: Boolean, lite: Boolean = ON): Boolean = if (lite) false else saved

    fun autoServiceCheck(saved: Boolean, lite: Boolean = ON): Boolean = if (lite) false else saved

    fun appAwareFailover(saved: Boolean, lite: Boolean = ON): Boolean = if (lite) false else saved

    /** Lite: движок hev выключен всегда (32-битная hev в Lite пока не используется: процесс на планшете 64-битный). */
    fun hevEngine(saved: Boolean, lite: Boolean = ON): Boolean = if (lite) false else saved

    /** Режимы, данные которых при импорте копии пропускаются. */
    fun skipModes(lite: Boolean = ON): Set<Mode> = if (lite) setOf(Mode.WHITELIST) else emptySet()

    // ---- по умолчанию выключено (4.2): другое значение по умолчанию, пользователь может включить ----
    fun defaultAnimations(lite: Boolean = ON): Boolean = !lite

    fun defaultFactsTicker(base: Boolean, lite: Boolean = ON): Boolean = if (lite) false else base

    fun defaultSlowSwitch(lite: Boolean = ON): Boolean = !lite

    fun defaultOnlyCharging(base: Boolean, lite: Boolean = ON): Boolean = if (lite) true else base

    fun defaultRecheckSpeed(base: Int, gentle: Int, lite: Boolean = ON): Int = if (lite) gentle else base

    /** Строка журнала при старте (4.6); null вне Lite. */
    fun startLine(lite: Boolean = ON): String? = if (!lite) null
        else "LITE: проб $PROBE_PARALLEL, TCP $TCP_PARALLEL, перепроверка $RECHECK_MAX, сбор $CONFIGS_PER_SOURCE/$CONFIGS_TOTAL, файл до ${MAX_BODY_CHARS / 1000} тыс. символов, память $MEM_HIGH_MB/$MEM_CRITICAL_MB МБ"
}
