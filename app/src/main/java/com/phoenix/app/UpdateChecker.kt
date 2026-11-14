package com.phoenix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Итог проверки: что нашли и удалось ли дозвониться до GitHub. */
data class UpdateOutcome(val reached: Boolean, val release: ReleaseInfo?)

/**
 * Проверка новой версии: раз в сутки (и по кнопке в настройках) смотрит последний релиз на GitHub.
 * Ничего не скачивает и не ставит: при новой версии показывает отдельное уведомление и плашку в приложении со ссылкой на страницу релиза.
 */
object UpdateChecker {
    private const val CHANNEL_ID = "phoenix_update"
    private const val NOTIFICATION_ID = 4712
    private const val DAY_MS = 24L * 3600 * 1000

    fun currentVersion(context: Context): String =
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    /** Сеть; вызывать не с главного потока. Сначала напрямую, затем (если VPN включён) через его внутренний вход. */
    fun fetch(context: Context): UpdateOutcome {
        val ua = "Phoenix/" + currentVersion(context)
        var body: String? = try {
            val c = URL(UpdateCheck.API_URL).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText().take(256 * 1024) } else null
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }
        val port = LocalProxy.port
        val auth = LocalProxy.auth
        if (body == null && port > 0 && auth != null) {
            body = try {
                LocalSocks.get(port, auth, UpdateCheck.API_URL, connectTimeoutMs = 10_000, readTimeoutMs = 10_000, follow = true, userAgent = ua).use { r ->
                    if (r.code == 200) r.stream().bufferedReader().use { it.readText().take(256 * 1024) } else null
                }
            } catch (_: Exception) {
                null
            }
        }
        return UpdateOutcome(body != null, body?.let { UpdateCheck.parse(it) })
    }

    /** Проверить сейчас, записать итог в настройки и (если версия новая и о ней ещё не сообщали) показать уведомление. */
    fun run(context: Context, notify: Boolean = true): UpdateOutcome {
        val settings = AppSettings(context)
        val out = fetch(context)
        if (out.reached) settings.updateCheckedAt = System.currentTimeMillis()
        val rel = out.release
        if (rel != null) {
            settings.updateTag = rel.tag
            settings.updateUrl = rel.url
            settings.updateApkUrl = rel.apkUrl
            settings.updateApkSha = rel.apkSha256
            settings.updateApkSize = rel.apkSize
            val local = currentVersion(context)
            if (notify && UpdateCheck.shouldNotify(rel.tag, local, settings.updateNotified)) {
                if (showNotification(context, rel)) settings.updateNotified = rel.tag
            }
            try { Storage(context).log("UPDATE: последняя версия ${rel.tag}, установлена $local" + if (UpdateCheck.isNewer(rel.tag, local)) " — доступно обновление" else "") } catch (_: Throwable) { }
        } else {
            try { Storage(context).log(if (out.reached) "UPDATE: ответ GitHub не похож на релиз" else "UPDATE: GitHub не ответил") } catch (_: Throwable) { }
        }
        return out
    }

    /** Давно не проверяли (больше суток) — проверить при открытии приложения. */
    fun due(context: Context): Boolean = System.currentTimeMillis() - AppSettings(context).updateCheckedAt > DAY_MS

    private fun showNotification(context: Context, rel: ReleaseInfo): Boolean = try {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Phoenix: обновления"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(SwitchRequest.EXTRA_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(tr("Phoenix: доступно обновление {}", rel.tag))
            .setContentText(tr("Нажмите, чтобы открыть страницу загрузки"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        nm.areNotificationsEnabled()
    } catch (_: Throwable) {
        false
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("phoenix_update_check", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val out = try { UpdateChecker.run(applicationContext) } catch (_: Throwable) { null }
        return if (out == null || !out.reached) Result.retry().takeIf { runAttemptCount < 3 } ?: Result.success() else Result.success()
    }
}
