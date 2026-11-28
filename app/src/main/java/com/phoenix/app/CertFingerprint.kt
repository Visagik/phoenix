package com.phoenix.app

import java.security.MessageDigest

/** SHA-256 сертификата подписи в нижнем регистре (одна функция для обеих веток [AppSigning]: API 28+ и старые). */
object CertFingerprint {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
