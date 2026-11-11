package com.phoenix.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Какое приложение сейчас на экране. Нужно только для варианта Б («переключить весь VPN при открытии приложения»);
 * требует особого разрешения «Доступ к данным об использовании».
 */
object ForegroundWatcher {
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Background.openAppSettings(context)
        }
    }

    /** Какое приложение на экране сейчас: последнее вышедшее вперёд за [lookbackMs] и не ушедшее с экрана. Для режима «Фокус». */
    fun currentLong(context: Context, lookbackMs: Long = 30 * 60_000L): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMs, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> pkg = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (e.packageName == pkg) pkg = null
            }
        }
        return pkg
    }

    /** Пакет приложения, которое вышло на передний план последним за [windowMs], либо null. */
    fun current(context: Context, windowMs: Long = 15_000): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
        }
        return pkg
    }

    /** Пакеты, которые не считаются «приложением пользователя»: само Phoenix, рабочий стол, шторка, клавиатуры. */
    fun systemPackages(context: Context): Set<String> {
        val out = HashSet<String>()
        out += context.packageName
        out += "com.android.systemui"
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, 0).forEach { out += it.activityInfo.packageName }
        } catch (_: Throwable) {
        }
        return out
    }

    /**
     * Приложения, которыми пользовались последние [windowMs] (свежие первыми, без Phoenix и рабочего стола).
     * Нужно, чтобы при смене сервера из самого Phoenix или с рабочего стола знать, что было открыто до этого.
     */
    fun recent(context: Context, windowMs: Long = 20 * 60_000L): List<String> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val e = UsageEvents.Event()
        val list = ArrayList<Pair<String, Long>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) list += e.packageName to e.timeStamp
        }
        return RecentApps.pick(list, systemPackages(context), now, windowMs)
    }
}
