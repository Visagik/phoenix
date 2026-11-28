package com.phoenix.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Здоровье одного источника. Формат строки в файле: url|active|fails|date|status|react */
data class SourceState(
    val active: Boolean = true,
    val fails: Int = 0,
    val lastDate: String = "",
    val status: String = "",
    val react: Int = 0,
) {
    fun toLine(url: String): String = listOf(url, active, fails, lastDate, status, react).joinToString("|")

    companion object {
        fun parse(line: String): Pair<String, SourceState>? {
            val parts = line.trim().split('|')
            if (parts.size < 6) return null
            val tail = parts.takeLast(5)
            val url = parts.dropLast(5).joinToString("|")
            if (url.isBlank()) return null
            return url to SourceState(
                active = tail[0] == "true",
                fails = tail[1].toIntOrNull() ?: 0,
                lastDate = tail[2],
                status = tail[3],
                react = tail[4].toIntOrNull() ?: 0,
            )
        }
    }
}

/** Жизненный цикл источника (раздел 10 документации Termux-версии). */
object SourcePolicy {
    const val MAX_CONSECUTIVE_FAILS = 5
    const val REACTIVATION_FAILS = 2
    const val REACTIVATE_DAYS = 7
    const val MAX_REACTIVATIONS = 3

    fun shouldReactivate(st: SourceState, today: LocalDate): Boolean {
        if (st.active || st.lastDate.isBlank() || st.react >= MAX_REACTIVATIONS) return false
        val last = try {
            LocalDate.parse(st.lastDate)
        } catch (e: Exception) {
            return false
        }
        return ChronoUnit.DAYS.between(last, today) >= REACTIVATE_DAYS
    }

    /**
     * Коды, по которым источник считается удалённым навсегда: 404 и 410 (файла или репозитория больше нет).
     * Не входят: 403 (защита от ботов, например GitLab отвечает так на чужие адреса), 401 (может быть платный ключ),
     * 451, 429, 5xx и 0 (блокировка оператором, перегрузка, обрыв: источник жив, просто сейчас не отдаётся).
     */
    val DEFINITIVE_CODES = setOf(404, 410)

    /** Сколько источников можно убрать автоматически за один сбор (остальные ждут следующего). */
    const val MAX_AUTO_REMOVE = 5

    /**
     * Источник удалён насовсем: прошлый сбор в другой день тоже дал 404 или 410, и сегодня снова 404 или 410.
     * Два ответа за один день (повторный сбор) не считаются: статус хранится с датой последней записи.
     */
    fun definitiveGone(prev: SourceState, code: Int, today: String): Boolean {
        if (code !in DEFINITIVE_CODES || rememberedDefinitive(prev) == null) return false
        val then = definitiveDate(prev)
        return then.isNotBlank() && then != today
    }

    /**
     * Дата запомненного окончательного ответа. В статусе: «FAIL:404@2026-11-24»; в старом формате («FAIL:404») даты нет, берём
     * дату последней записи. Отдельно от lastDate, которая обновляется при каждой неудаче и по которой [shouldReactivate]
     * отсчитывает неделю до возврата источника (Д4).
     */
    fun definitiveDate(st: SourceState): String = st.status.substringAfter('@', "").ifBlank { st.lastDate }

    /** Кандидат на автоудаление: url, код сегодня, дата прошлой записи, защищён ли. */
    data class Gone(val url: String, val code: Int, val prevDate: String, val isProtected: Boolean)

    /** Что делать с кандидатами: убрать (не больше [MAX_AUTO_REMOVE]), предупредить о защищённых, сколько осталось на следующий сбор. */
    data class GonePlan(val remove: List<Gone>, val warn: List<Gone>, val postponed: Int)

    fun planGone(candidates: List<Gone>): GonePlan {
        val warn = candidates.filter { it.isProtected }
        val rest = candidates.filter { !it.isProtected }
        return GonePlan(rest.take(MAX_AUTO_REMOVE), warn, (rest.size - MAX_AUTO_REMOVE).coerceAtLeast(0))
    }

    fun onSuccess(today: String) = SourceState(true, 0, today, "OK", 0)

    /**
     * Неудача источника. Счётчик неудач растёт всегда. Но запомненный окончательный ответ (FAIL:404 / FAIL:410) и дата этого ответа
     * не стираются неокончательным кодом (0, 403, 5xx…): на Wi-Fi источник мог отвечать 404, а через мобильный VPN — код 0,
     * и статус чередовался, так что [definitiveGone] не срабатывал никогда (журнал 24.11: codeberg zieng2, MrEndi).
     * Успех ([onSuccess]) и новый окончательный ответ запись заменяют, как раньше.
     */
    fun onFailure(st: SourceState, code: Int, today: String): SourceState {
        val fails = st.fails + 1
        val threshold = if (st.react > 0) REACTIVATION_FAILS else MAX_CONSECUTIVE_FAILS
        val rememberedGone = code !in DEFINITIVE_CODES && rememberedDefinitive(st) != null && st.lastDate.isNotBlank()
        // дата окончательного ответа переезжает в статус, а lastDate обновляется при каждой неудаче, как у любого источника (Д4)
        return if (rememberedGone) SourceState(fails < threshold, fails, today, "FAIL:${rememberedDefinitive(st)}@${definitiveDate(st)}", st.react)
        else SourceState(fails < threshold, fails, today, if (code in DEFINITIVE_CODES) "FAIL:$code@$today" else "FAIL:$code", st.react)
    }

    /** Запомненный окончательный код (404/410) из статуса, иначе null. */
    private fun rememberedDefinitive(st: SourceState): Int? =
        st.status.takeIf { it.startsWith("FAIL:") }?.removePrefix("FAIL:")?.substringBefore('@')?.toIntOrNull()?.takeIf { it in DEFINITIVE_CODES }
}
