package com.phoenix.app

import java.io.File

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
