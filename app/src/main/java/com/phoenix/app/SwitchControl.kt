package com.phoenix.app

import java.io.File

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
