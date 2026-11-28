package com.phoenix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Каналы уведомлений есть только с Android 8 (API 26): на старых версиях ничего создавать не нужно, уведомления идут без канала. */
object NotifChannels {
    fun ensure(context: Context, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT < 26) return
        create(context.getSystemService(NotificationManager::class.java), id, name, importance)
    }

    @androidx.annotation.RequiresApi(26)
    private fun create(nm: NotificationManager, id: String, name: String, importance: Int) {
        nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }
}
