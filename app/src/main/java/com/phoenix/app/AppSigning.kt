package com.phoenix.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Отпечатки (SHA-256) ключей подписи: установленного приложения и скачанного APK. Нужны, чтобы заранее понять, встанет ли обновление поверх. */
object AppSigning {
    fun ownCerts(context: Context): Set<String> = try {
        certs(context.packageManager.getPackageInfo(context.packageName, flags()))
    } catch (_: Throwable) {
        emptySet()
    }

    fun fileCerts(context: Context, path: String): Set<String> = try {
        certs(context.packageManager.getPackageArchiveInfo(path, flags()))
    } catch (_: Throwable) {
        emptySet()
    }

    // GET_SIGNING_CERTIFICATES — с API 28; на старых GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun flags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun certs(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        return if (Build.VERSION.SDK_INT >= 28) certs28(info) else certsOld(info)
    }

    @Suppress("DEPRECATION")
    private fun certsOld(info: PackageInfo): Set<String> = info.signatures.orEmpty().map { CertFingerprint.sha256(it.toByteArray()) }.toSet()

    // SigningInfo и его методы — API 28; тип появляется только в этой функции
    @androidx.annotation.RequiresApi(28)
    private fun certs28(info: PackageInfo): Set<String> {
        val si = info.signingInfo ?: return emptySet()
        // история ключей тоже годится: Android разрешает обновление, если новая подпись есть в истории установленного
        val all = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return all.orEmpty().map { CertFingerprint.sha256(it.toByteArray()) }.toSet()
    }
}
