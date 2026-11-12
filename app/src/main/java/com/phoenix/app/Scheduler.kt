package com.phoenix.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Запуск сбора. Три независимых «страховки», потому что Android/MIUI может отложить любую из них:
 *  1) точный будильник на выбранное время (AlarmManager, работает и в Doze) -> срочная задача WorkManager;
 *  2) периодическая задача WorkManager (запасная);
 *  3) догон: при старте процесса / после загрузки телефона, если сегодняшнего файла нет, а время уже прошло.
 * Дубли безопасны: воркер пропускает запуск, если сегодняшний файл уже есть.
 */
object Scheduler {
    private const val NIGHTLY = "phoenix_nightly"
    private const val RUN = "phoenix_run"
    const val KEY_TRIGGER = "trigger"

    private fun constraints(onlyCharging: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresCharging(onlyCharging)
        .build()

    /** Всё запланировать и догнать пропущенное. Вызывается при каждом старте процесса. */
    fun ensureScheduled(context: Context) {
        scheduleNightly(context, replace = false)
        AlarmScheduler.arm(context)
        TargetsWorker.schedulePeriodic(context)
        catchUpIfNeeded(context, trigger = "catch-up")
    }

    /** Применить новое время / ограничения немедленно. */
    fun reschedule(context: Context) {
        scheduleNightly(context, replace = true)
        AlarmScheduler.arm(context)
    }

    /** Запасная периодическая задача. */
    fun scheduleNightly(context: Context, replace: Boolean) {
        val s = AppSettings(context)
        val interval = s.scheduleKind == ScheduleKind.INTERVAL
        val delayMs = if (interval) {
            val now = System.currentTimeMillis()
            (Schedule.nextIntervalAt(s.lastSuccessAt, now, s.intervalHours) - now).coerceAtLeast(0L)
        } else {
            Duration.between(LocalDateTime.now(), Schedule.nextRun(LocalDateTime.now(), s.runHour, s.runMinute)).toMillis()
        }
        val periodHours = if (interval) s.intervalHours.toLong() else 24L

        val request = PeriodicWorkRequestBuilder<PhoenixWorker>(periodHours, TimeUnit.HOURS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(s.onlyCharging))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()

        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NIGHTLY, policy, request)
    }

    /** Если сегодняшнего сбора нет, а время давно прошло — запустить сейчас (при первой возможности). */
    fun catchUpIfNeeded(context: Context, trigger: String) {
        val s = AppSettings(context)
        val need = if (s.scheduleKind == ScheduleKind.INTERVAL) {
            Schedule.intervalCatchUp(s.lastSuccessAt, s.lastAttemptAt, System.currentTimeMillis(), s.intervalHours)
        } else {
            Schedule.catchUpNeeded(
                now = LocalDateTime.now(),
                hour = s.runHour,
                minute = s.runMinute,
                doneToday = Storage.allDoneToday(context),
                lastAttemptMillis = s.lastAttemptAt,
                nowMillis = System.currentTimeMillis(),
            )
        }
        if (need) enqueue(context, trigger)
    }

    /** Разовый запуск. Срочная задача — не откладывается Doze, как обычная. */
    fun enqueue(context: Context, trigger: String) {
        val s = AppSettings(context)
        val manual = trigger == "manual"
        val delaySec = ColdStart.catchUpDelaySec(trigger, s.lastSuccessAt)
        val builder = OneTimeWorkRequestBuilder<PhoenixWorker>()
            .setConstraints(constraints(onlyCharging = s.onlyCharging && !manual))
            .setInputData(workDataOf(KEY_TRIGGER to trigger))
        // срочная задача не может быть отложенной: при первом запуске выбираем отсрочку, чтобы дать экрану прогрузиться
        val request = if (delaySec > 0) builder.setInitialDelay(delaySec, TimeUnit.SECONDS).build()
        else builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, ExistingWorkPolicy.KEEP, request)
    }

    /** «Собрать сейчас» из интерфейса. */
    fun runNow(context: Context) = enqueue(context, "manual")
}

/** Будильник на выбранное время. */
object AlarmScheduler {
    private const val REQUEST_CODE = 1

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /**
     * Ставит (или переставляет) будильник: на выбранное время дня или, в режиме «раз в несколько часов», на срок после
     * последнего удачного сбора. [fromNow] — сбор только что запущен и ещё не завершён: считаем срок от текущего момента.
     */
    /** Повторный будильник через [delayMs]: после пропуска без сети или неудачного сбора (иначе следующий срок — через полный интервал). */
    fun armRetry(context: Context, delayMs: Long) {
        val s = AppSettings(context)
        val millis = System.currentTimeMillis() + delayMs
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        try {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        s.alarmAt = millis
    }

    fun arm(context: Context, fromNow: Boolean = false) {
        val s = AppSettings(context)
        val millis = if (s.scheduleKind == ScheduleKind.INTERVAL) {
            val now = System.currentTimeMillis()
            Schedule.nextIntervalAt(if (fromNow) now else s.lastSuccessAt, now, s.intervalHours)
        } else {
            Schedule.nextRun(LocalDateTime.now(), s.runHour, s.runMinute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)

        var exact = canScheduleExact(context)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (_: SecurityException) {
            // разрешение на точные будильники отозвали между проверкой и вызовом
            exact = false
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        s.alarmAt = millis
        s.alarmExact = exact
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.enqueue(context, "alarm")
        AlarmScheduler.arm(context, fromNow = true) // следующий срок; после удачного сбора будильник уточнится
    }
}

/** После перезагрузки телефона и обновления приложения будильники сбрасываются — ставим заново и догоняем. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleNightly(context, replace = false)
        AlarmScheduler.arm(context)
        Scheduler.catchUpIfNeeded(context, trigger = "boot")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) PhoenixVpnService.autoStart(context, "загрузка телефона")
    }
}

class PhoenixWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(Scheduler.KEY_TRIGGER) ?: "worker"
        val ctx = applicationContext
        val settings = AppSettings(ctx)
        val manual = trigger == "manual"
        if (!manual && NetInfo.kind(ctx) == NetInfo.Kind.NONE) {
            // без сети сбор всё равно закончится «ни один источник не ответил» и потратит попытку (в логе — пачки таких сборов)
            Storage(ctx).log("SKIP: нет сети — сбор отложен [$trigger]")
            if (Schedule.needsRetry(settings.scheduleKind == ScheduleKind.INTERVAL, settings.lastSuccessAt, System.currentTimeMillis(), settings.intervalHours, Storage.allDoneToday(ctx))) {
                // будильник уже переставлен на полный интервал вперёд — без этого сбор ждал бы следующего срока или запуска приложения
                AlarmScheduler.armRetry(ctx, Schedule.retryDelayMs(noNetwork = true))
                Storage(ctx).log("SCHED: сети нет — следующая попытка через ${Schedule.NO_NETWORK_RETRY_MS / 60_000} мин или сразу при появлении сети")
            }
            return if (runAttemptCount >= 4) Result.success() else Result.retry()
        }
        GeoIp.source = { ctx.assets.open("geoip4.bin") }

        val interval = settings.scheduleKind == ScheduleKind.INTERVAL
        // Будильник, периодическая задача и догон могут сработать подряд — один сбор на срок.
        if (!manual) {
            if (interval && !Schedule.intervalDue(settings.lastSuccessAt, System.currentTimeMillis(), settings.intervalHours)) {
                Storage(ctx).log("SKIP: срок ещё не вышел (раз в ${settings.intervalHours} ч) [$trigger]")
                return Result.success()
            }
            if (!interval && Storage.allDoneToday(ctx)) {
                Storage(ctx).log("SKIP: сегодняшние файлы уже есть [$trigger]")
                return Result.success()
            }
        }

        // По расписанию собираем каждый включённый режим (при ежедневном — те, где сегодня ещё нет файла); по кнопке — только текущий.
        val enabled = Storage.enabledModes(ctx)
        val modes = if (manual) listOf(Storage(ctx).mode)
        else if (interval) enabled
        else enabled.filter { !Storage(ctx, it).hasEnrichedToday() }

        settings.recordAttempt(trigger)
        val summaries = ArrayList<String>()
        var allOk = true
        for (m in modes) {
            val outcome = Collector(ctx, mode = m).run(trigger)
            allOk = allOk && outcome.ok
            summaries += if (modes.size > 1) "${m.title}: ${outcome.summary}" else outcome.summary
        }
        settings.recordResult(summaries.joinToString("; "))
        if (!allOk) {
            AlarmScheduler.armRetry(ctx, Schedule.retryDelayMs(noNetwork = false))
            Storage(ctx).log("SCHED: сбор не удался — повторю через ${Schedule.INTERVAL_RETRY_MS / 60_000} мин")
        }
        if (allOk && modes.containsAll(enabled)) {
            settings.lastSuccessAt = System.currentTimeMillis()
            AlarmScheduler.arm(ctx) // следующий срок считается от этого сбора
        }

        // Свежие данные — повод перепроверить серверы целей (если они есть).
        // Сначала полная перепроверка конфигов (она же проверяет сервисы, список обхода и ставит приоритеты, а в конце сама запускает
        // перепроверку целей); если перепроверка не стартовала (выключена или была недавно) — только цели.
        if (allOk && !Recheck.enqueueIfDue(ctx, Storage(ctx).mode)) TargetsWorker.enqueue(ctx)
        return if (allOk || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    /** Нужно для срочных задач на Android 10–11 (на 12+ не показывается). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phoenix", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Phoenix")
            .setContentText("Collecting configs…")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL_ID = "phoenix_run"
        private const val NOTIFICATION_ID = 4711
    }
}
