package com.phoenix.app

import java.util.Base64
import java.util.Random

/** Ключ XUDP для ядра: base64url без «=» от ровно 32 байт (иначе ядро отвечает «xray.xudp.basekey: invalid value»). */
object XudpKey {
    fun make(random: Random = java.security.SecureRandom()): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isValid(key: String): Boolean = try {
        !key.contains('=') && Base64.getUrlDecoder().decode(key).size == 32
    } catch (_: IllegalArgumentException) {
        false
    }
}
