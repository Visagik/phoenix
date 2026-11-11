package com.phoenix.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** При запуске записывает в журнал, почему система закрыла прошлые процессы приложения (по одной записи один раз). */
object ExitLog {
    fun report(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val prefs = context.getSharedPreferences("phoenix", Context.MODE_PRIVATE)
            val seen = prefs.getLong("exit_seen_ts", 0L)
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val list = am.getHistoricalProcessExitReasons(context.packageName, 0, 30).filter { it.timestamp > seen }
            if (list.isEmpty()) return
            val st = Storage(context)
            for (e in list.sortedBy { it.timestamp }) {
                if (!ExitReasons.notable(e.reason)) continue
                if (KillBanner.systemKill(e.reason)) prefs.edit().putLong("kill_ts", e.timestamp).putInt("kill_code", e.reason).apply()
                st.log(ExitReasons.line(e.reason, e.description.orEmpty(), e.importance, e.rss))
                // ANR: системная трасса показывает, что делал главный поток и кого ждал
                if (e.reason == 6) try {
                    e.traceInputStream?.use { ins -> st.log("ANR: " + ExitReasons.anrSummary(ins.bufferedReader(Charsets.UTF_8).let { r -> val b = CharArray(60_000); val n = r.read(b); if (n > 0) String(b, 0, n) else "" })) }
                } catch (_: Throwable) { }
            }
            prefs.edit().putLong("exit_seen_ts", list.maxOf { it.timestamp }).apply()
        } catch (_: Throwable) {
        }
    }
}
