package com.phoenix.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager

/** Часы активности приложения (один общий экземпляр на процесс). */
object ActivityStore {
    @Volatile private var inst: ActivityHours? = null
    fun get(context: Context): ActivityHours = inst ?: synchronized(this) {
        inst ?: ActivityHours(java.io.File(context.applicationContext.filesDir, "activity_hours.tsv")).also { inst = it }
    }
}

/** Сейчас тихие часы: свои (если включены) или автоматические по часам активности. */
object QuietHours {
    fun now(context: Context): Boolean = try {
        val s = AppSettings(context)
        val hour = java.time.LocalTime.now().hour
        if (s.quietManual && QuietWindow.inside(hour, s.quietFrom, s.quietTo)) true
        else s.quietAuto && ActivityStore.get(context).isQuietNow()
    } catch (_: Throwable) { false }
}

/** Состояние питания и сети для решения «можно ли сейчас делать тяжёлое». */
object PowerInfo {
    fun charging(context: Context): Boolean = try {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val s = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Throwable) { false }

    fun percent(context: Context): Int = try {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (l < 0 || sc <= 0) 100 else l * 100 / sc
    } catch (_: Throwable) { 100 }

    fun metered(context: Context): Boolean = try {
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    } catch (_: Throwable) { false }

    /** Включён режим энергосбережения: тяжёлое фоновое не запускаем (кроме ручного). */
    fun powerSave(context: Context): Boolean = try {
        context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
    } catch (_: Throwable) { false }

    /** Заряд низкий и телефон не заряжается (или включена экономия): проверки туннеля реже. */
    fun lowBattery(context: Context): Boolean = HealthPace.lowBattery(percent(context), charging(context), powerSave(context))

    /** Телефон заметно нагрелся (система сообщает «умеренный» перегрев и выше). */
    fun hot(context: Context): Boolean = try {
        context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
    } catch (_: Throwable) { false }

    fun heavyAllowed(context: Context, lastRunAt: Long, manual: Boolean = false): Boolean {
        if (!manual && Focus.active(context)) return false // открыто приложение из целей и включён «Фокус»
        val hours = if (lastRunAt <= 0) 999L else (System.currentTimeMillis() - lastRunAt) / 3_600_000L
        return HeavyGate.allow(manual, charging(context), percent(context), metered(context), hours, quiet = QuietHours.now(context), hot = hot(context) || powerSave(context))
    }
}

/** Общий для процесса учёт «сколько параллельных проверок выдерживает сеть» (по файлу режима). */
object GovernedLimits {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ParallelGovernor>()
    fun get(storage: Storage): ParallelGovernor =
        map.getOrPut(storage.parallelFile.path) { ParallelGovernor(storage.parallelFile) }
}

/** Режим «Фокус»: открыто приложение из ваших целей — тяжёлое ждёт. Нужен доступ к данным об использовании. */
object Focus {
    fun active(context: Context): Boolean = try {
        if (!AppSettings(context).focusMode || !ForegroundWatcher.hasAccess(context)) false
        else {
            val apps = Mode.entries.flatMap { m -> TargetStore(Storage(context, m).targetsFile).load() }
                .filter { it.kind == TargetKind.APP }.map { it.value }.toSet()
            FocusRule.active(ForegroundWatcher.currentLong(context), apps)
        }
    } catch (_: Throwable) { false }
}
