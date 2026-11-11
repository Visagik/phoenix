package com.phoenix.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Состояние «фоновой надёжности» для экрана настроек и кнопки перехода в системные настройки. */
data class BackgroundStatus(
    val ignoringBatteryOptimization: Boolean,
    val exactAlarmAllowed: Boolean,
    val alarmText: String,
    val lastRunText: String,
)

object Background {
    fun status(context: Context): BackgroundStatus {
        val s = AppSettings(context)
        val pm = context.getSystemService(PowerManager::class.java)
        val alarm = if (s.alarmAt == 0L) "не поставлен" else {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.alarmAt), ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(t) + if (s.alarmExact) " (точный)" else " (неточный)"
        }
        return BackgroundStatus(
            ignoringBatteryOptimization = pm.isIgnoringBatteryOptimizations(context.packageName),
            exactAlarmAllowed = AlarmScheduler.canScheduleExact(context),
            alarmText = alarm,
            lastRunText = s.lastRunText(),
        )
    }

    private fun pkgUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun start(context: Context, intent: Intent, failText: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, failText, Toast.LENGTH_LONG).show()
        }
    }

    /** Разрешение «не ограничивать батарею» для приложения. */
    fun requestIgnoreBatteryOptimization(context: Context) = start(
        context,
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(pkgUri(context)),
        "Не удалось открыть настройки батареи",
    )

    /** Экран «Будильники и напоминания» (Android 12+). */
    fun requestExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        start(
            context,
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(pkgUri(context)),
            "Не удалось открыть настройки будильников",
        )
    }

    private var cachedFirmware: FirmwareInfo? = null

    /** Системное свойство через getprop (читать его напрямую через скрытые API нельзя). */
    private fun prop(key: String): String = try {
        val proc = Runtime.getRuntime().exec(arrayOf("getprop", key))
        proc.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (_: Exception) {
        ""
    }

    /** Какая прошивка на телефоне (определяется один раз). */
    fun firmware(): FirmwareInfo = cachedFirmware ?: FirmwareDetect.detect(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        display = Build.DISPLAY.orEmpty(),
        sdk = Build.VERSION.SDK_INT,
        props = { prop(it) },
    ).also { cachedFirmware = it }

    /** Открывает первый экран из списка, который есть на этом телефоне; иначе — настройки приложения. */
    fun openFirst(context: Context, screens: List<ScreenSpec>) {
        for (spec in screens) {
            try {
                context.startActivity(Intent().setComponent(ComponentName(spec.pkg, spec.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        openAppSettings(context)
        Toast.makeText(context, tr("Нужного экрана в этой прошивке нет — открыл настройки приложения"), Toast.LENGTH_LONG).show()
    }

    /** Экран «Экономия трафика»: приложению нужно разрешить неограниченные данные в фоне. */
    fun requestUnrestrictedData(context: Context) = start(
        context,
        Intent(android.provider.Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).setData(pkgUri(context)),
        "Не удалось открыть настройки экономии трафика",
    )

    fun dataSaverText(context: Context): String = try {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        when (cm.restrictBackgroundStatus) {
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "выключена"
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "включена, Phoenix в исключениях"
            else -> "включена, Phoenix ограничен"
        }
    } catch (_: Exception) {
        "неизвестно"
    }

    /** Какая прошивка у телефона (по производителю). */
    fun vendor(): OemGuide.Vendor = OemGuide.vendorFor(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.BRAND.orEmpty(), try { firmware().kind } catch (_: Throwable) { FirmwareKind.STOCK })

    /** Экран автозапуска своей прошивки; если такого экрана нет или он не открылся — настройки приложения. */
    fun openAutostart(context: Context) {
        for ((pkg, cls) in OemGuide.autostart(vendor())) {
            try {
                context.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        openAppSettings(context)
    }

    fun openNotificationSettings(context: Context) = start(
        context,
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
        "Не удалось открыть настройки уведомлений",
    )

    fun openAppSettings(context: Context) = start(
        context,
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkgUri(context)),
        "Не удалось открыть настройки приложения",
    )
}
