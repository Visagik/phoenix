package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

data class Candidate(
    val link: String,
    val identity: String,
    val days: Int,
    val mbps: Double,
    val latencyMs: Long,
    /** Сколько из выбранных сервисов открывается через этот конфиг (его «возможности») */
    val caps: Int = 0,
    /** Надёжность конфига в текущей сети по накопленному опыту (0.5 — данных нет). */
    val rel: Double = 0.5,
    /** В этой сети конфиг уже несколько раз подводил: его ставят в самый конец. */
    val bad: Boolean = false,
    /** Недавно (последние 30 минут) на нём оборвалась сессия: в конец очереди, потом второй шанс. */
    val quarantined: Boolean = false,
    /** В этой сети держится стабильно: не меньше 8 удач и надёжность ≥ 90%. Лёгкий бонус в рейтинге. */
    val steady: Boolean = false,
    /** Свежая проверка показала, что конфиг отвечает (не «неизвестно»): таких пробуют первыми. */
    val alive: Boolean = false,
    /** По названию конфиг предназначен для другого режима (например, «[Белые списки]» в чёрных): пробуется после остальных. */
    val mismatch: Boolean = false,
    /** Конфиг взят из списка другого режима (белых при поиске в чёрных): так его можно подмешать в очередь выше конца. */
    val foreign: Boolean = false,
    /** Когда-то не ответил (возможно, в другой сети): остаётся в игре, но после тех, о ком такого не известно. */
    val deadMark: Boolean = false,
    /** Приоритет ссылки-источника, из которой пришёл конфиг (−1 низкий, 0 обычный, 1 высокий). */
    val prio: Int = 0,
    /** Сервер для узкой цели (Госуслуги, торренты) или с российским выходом в чёрных списках: для общего VPN он хуже обычных. */
    val unfit: Boolean = false,
    /** Страна выхода сервера (по названию/адресу), пусто — неизвестна. */
    val cc: String = "",
    /** Название прямо говорит, что сервер для текущего режима («[WL] …» в белых списках): для режима он надёжнее случайных. */
    val modeHint: Boolean = false,
    /** Тип конфига (Reality, Trojan…) в этой сети: 0 — выручает, 1 — мало данных, 2 — подводит. См. [ProtocolLearning]. */
    val protoBucket: Int = 1,
) {
    /** Хост сервера: конфиги с одним хостом и разными протоколами — один и тот же сервер. */
    val host: String get() = ConfigIdentity.host(link)
}

object Ranking {
    /** Сначала стабильные (3+ дня), потом по скорости, потом по задержке; не измеренное — в конце. */
    fun rank(list: List<Candidate>): List<Candidate> = list.sortedWith(
        compareBy<Candidate> { it.bad }
            .thenBy { it.quarantined }
            .thenBy { it.mismatch }
            .thenBy { it.unfit }
            .thenByDescending { it.alive }
            .thenBy { it.prio <= SourcePriority.RESERVE }
            .thenByDescending { PoolPolicy.preferred(it) }
            .thenBy { it.deadMark }
            .thenByDescending { it.modeHint }
            .thenBy { it.protoBucket }
            .thenByDescending { it.prio }
            .thenByDescending { it.steady }
            .thenByDescending { it.days >= 3 }
            .thenByDescending { it.caps }
            .thenByDescending { Math.round(it.rel * 4) }
            .thenByDescending { it.mbps }
            .thenBy { if (it.latencyMs <= 0) Long.MAX_VALUE else it.latencyMs }
    )

    /**
     * Оставляет по одному конфигу на сервер (порядок рейтинга): запасной с тем же сервером, но другим протоколом,
     * упадёт вместе с основным. Конфиги без хоста не склеиваются.
     */
    fun distinctServers(ranked: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        return ranked.filter { it.host.isEmpty() || seen.add(it.host) }
    }

    /** Сколько дней результат «не отвечает» считается свежим: потом конфигу дают второй шанс. */
    const val DEAD_TTL_DAYS = 2L

    /**
     * Кандидаты из списка конфигов. Мёртвые по свежей проверке отбрасываются; если проверка старая
     * или адрес сервера с тех пор сменился (то же имя, новый сервер) — конфиг снова в игре.
     */
    fun candidates(
        rows: List<ConfigRow>,
        checks: Map<String, CheckResult>,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        history: ((String) -> HistStat?)? = null,
        mode: Mode? = null,
        /**
         * Не выбрасывать «мёртвых»: конфиг мог не отвечать в другой сети (белые списки), а здесь жить.
         * Для поиска запасных нужны все конфиги; отметка «не отвечал» только ставит их в очередь позже.
         */
        includeDead: Boolean = false,
        /** Приоритет конфига по ключу проверки (см. Storage.priorityView). */
        priority: ((String) -> Int)? = null,
        /** Сервисы, выбранные пользователем: «рабочесть» считается только по ним (null — по всем). */
        svcIds: Set<String>? = null,
    ): List<Candidate> = withProtocolLearning(candidatesRaw(rows, checks, today, history, mode, includeDead, priority, svcIds), history).let { raw ->
        // при поиске (includeDead) запасные («Только если ничего нет») не отбрасываются: они стоят после основных, но поиск до них дойдёт
        PoolPolicy.apply(if (includeDead) raw else PoolPolicy.applyReserve(raw))
    }

    /** Добавляет к кандидатам оценку типа конфига по опыту в этой сети. */
    private fun withProtocolLearning(list: List<Candidate>, history: ((String) -> HistStat?)?): List<Candidate> {
        if (history == null || list.isEmpty()) return list
        val rates = ProtocolLearning.rates(list.map { it.link to history(it.identity) })
        if (rates.values.none { it.total >= ProtocolLearning.MIN_SAMPLES }) return list
        return list.map { c -> val b = ProtocolLearning.bucket(ProtocolLearning.kindOf(c.link), rates); if (b == 1) c else c.copy(protoBucket = b) }
    }

    private fun candidatesRaw(
        rows: List<ConfigRow>,
        checks: Map<String, CheckResult>,
        today: java.time.LocalDate,
        history: ((String) -> HistStat?)?,
        mode: Mode?,
        includeDead: Boolean,
        priority: ((String) -> Int)?,
        svcIds: Set<String>?,
    ): List<Candidate> =
        rows.mapNotNull { row ->
            val check = checks[CheckStore.keyOf(row.raw)]
            var deadMark = false
            if (check != null && check.dead && !check.staleFor(row.raw)) {
                val checked = try {
                    java.time.LocalDate.parse(check.date)
                } catch (_: Exception) {
                    null
                }
                val fresh = checked == null || !checked.isBefore(today.minusDays(DEAD_TTL_DAYS))
                if (fresh) {
                    if (!includeDead) return@mapNotNull null
                    deadMark = true
                }
            }
            val identity = ConfigIdentity.key(row.raw)
            val stat = history?.invoke(identity)
            Candidate(
                link = row.raw,
                identity = identity,
                days = row.days,
                mbps = check?.mbps ?: -1.0,
                latencyMs = check?.baselineMs ?: 0,
                caps = check?.services?.entries?.count { (svcIds == null || it.key in svcIds) && CheckServices.opened(it.value) } ?: 0,
                rel = stat?.reliability ?: 0.5,
                bad = stat?.bad == true,
                quarantined = Quarantine.active(identity),
                steady = stat != null && stat.ok >= 8 && stat.reliability >= 0.9,
                alive = check != null && !check.dead && !check.staleFor(row.raw) && isFresh(check.date, today),
                mismatch = mode != null && ListHint.of(row.displayName).let { it != null && it != mode },
                deadMark = deadMark,
                prio = priority?.invoke(CheckStore.keyOf(row.raw)) ?: 0,
                // в белых списках российские и «целевые» серверы как раз рабочие; в чёрных YouTube и прочее через них не открыть
                cc = row.cc?.uppercase().orEmpty(),
                modeHint = mode != null && ListHint.of(row.displayName) == mode,
                unfit = mode == Mode.BLACKLIST &&
                    (ListHint.special(row.displayName) || row.cc.equals("RU", true) || ListHint.russian(row.displayName)),
            )
        }

    private fun isFresh(date: String, today: java.time.LocalDate): Boolean = try {
        !java.time.LocalDate.parse(date).isBefore(today.minusDays(1))
    } catch (_: Exception) {
        false
    }

    /** Кого пробовать при падении туннеля: лучшие по рейтингу, кроме уже испробованных и текущего. */
    fun failoverOrder(
        candidates: List<Candidate>,
        tried: Set<String>,
        currentIdentity: String,
        limit: Int = 8,
        currentHost: String = "",
        triedHosts: Set<String> = emptySet(),
    ): List<Candidate> =
        distinctServers(
            rank(candidates).filter {
                it.identity != currentIdentity && it.identity !in tried &&
                    (it.host.isEmpty() || (it.host != currentHost && it.host !in triedHosts))
            }
        ).take(limit)
}
