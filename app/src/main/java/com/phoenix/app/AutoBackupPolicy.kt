package com.phoenix.app

/** Когда делать автокопию и какие старые удалять. */
object AutoBackupPolicy {
    const val EVERY_MS = 7L * 24 * 3600 * 1000
    const val KEEP = 3
    const val PREFIX = "phoenix-auto-"

    /** Пора, если копии не было, часы шли назад или прошла неделя. */
    fun due(lastAt: Long, now: Long): Boolean = lastAt <= 0 || now < lastAt || now - lastAt >= EVERY_MS

    /** Имена файлов автокопий, которые нужно удалить: остаются [keep] самых новых (имя содержит дату, поэтому порядок по имени — по времени). */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(PREFIX) }.sortedDescending().drop(keep)

    fun fileName(date: String): String = "$PREFIX$date.txt"
}
