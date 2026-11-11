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

    fun onSuccess(today: String) = SourceState(true, 0, today, "OK", 0)

    fun onFailure(st: SourceState, code: Int, today: String): SourceState {
        val fails = st.fails + 1
        val threshold = if (st.react > 0) REACTIVATION_FAILS else MAX_CONSECUTIVE_FAILS
        return SourceState(fails < threshold, fails, today, "FAIL:$code", st.react)
    }
}
