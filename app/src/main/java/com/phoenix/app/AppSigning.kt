package com.phoenix.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.security.MessageDigest

/** Отпечатки (SHA-256) ключей подписи: установленного приложения и скачанного APK. Нужны, чтобы заранее понять, встанет ли обновление поверх. */
object AppSigning {
    fun ownCerts(context: Context): Set<String> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        certs(info.signingInfo)
    } catch (_: Throwable) {
        emptySet()
    }

    fun fileCerts(context: Context, path: String): Set<String> = try {
        val info = context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        certs(info?.signingInfo)
    } catch (_: Throwable) {
        emptySet()
    }

    private fun certs(si: SigningInfo?): Set<String> {
        if (si == null) return emptySet()
        // история ключей тоже годится: Android разрешает обновление, если новая подпись есть в истории установленной
        val all = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return all.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}
