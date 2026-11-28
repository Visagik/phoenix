package com.phoenix.app

/** Base64 без java.util.Base64 (он есть только с Android 8; у Lite minSdk 23). Чистая логика. */
object B64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Декодирует и обычный, и url-алфавит; «=» в конце необязательны. Мусор — IllegalArgumentException. */
    fun decode(s: String): ByteArray {
        val body = s.trimEnd('=')
        require(body.length % 4 != 1) { "bad base64 length" }
        val out = java.io.ByteArrayOutputStream(body.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in body) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> throw IllegalArgumentException("bad base64 char")
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    /** base64url без «=». */
    fun encodeUrlNoPad(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xFF else 0)
            for (k in 0..n) sb.append(URL[(v shr (18 - 6 * k)) and 63])
            i += 3
        }
        return sb.toString()
    }
}
