package com.phoenix.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Android-часть Lite: журнал устройства и корни Let's Encrypt. Числа и флаги — в [LiteProfile]. */
object LiteEnv {
    /**
     * Android 6 не знает корни Let's Encrypt (ISRG X1/X2): добавляем их к системным (TlsRoots). Только в Lite; вызывать до первого запроса.
     * Отпечатки сверяются с ожидаемыми; несовпавший файл не берётся.
     */
    fun installTls(context: Context): String? {
        if (!LiteProfile.ON) return null
        try {
            val res = context.resources
            val loaded = TlsRoots.load(mapOf(
                "isrg_root_x1" to { res.openRawResource(R.raw.isrg_root_x1) },
                "isrg_root_x2" to { res.openRawResource(R.raw.isrg_root_x2) },
            ))
            val f = TlsRoots.socketFactory(TlsRoots.trustManager(loaded.certs))
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(f)
            TlsRoots.factory = f
            return ("TLS: LITE — к системным корням добавлено ${loaded.certs.size} (ISRG X1/X2)" + (if (loaded.rejected.isEmpty()) "" else "; не прошли проверку отпечатка: ${loaded.rejected.joinToString()}"))
        } catch (e: Throwable) {
            return "TLS: LITE — не удалось добавить корни (${e.javaClass.simpleName}); работают только системные"
        }
    }

    /** Одна строка в журнал при старте Lite: что за устройство (заменяет команды ADB). В обычной сборке ничего не пишет. */
    fun logEnv(context: Context, storage: Storage) {
        if (!LiteProfile.ON) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            LiteProfile.startLine()?.let { storage.log(it) }
            storage.log(
                "ENV: LITE — Android ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}, memoryClass ${am.memoryClass} МБ, " +
                    "largeMemoryClass ${am.largeMemoryClass} МБ, lowRam ${am.isLowRamDevice}, ядро Xray ${if (XrayCore.present) "есть" else "нет"}",
            )
        } catch (e: Throwable) {
            storage.log("ENV: LITE — не удалось определить устройство (${e.javaClass.simpleName})")
        }
    }
}
