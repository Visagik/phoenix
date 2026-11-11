package com.phoenix.app

import java.io.File
import java.io.RandomAccessFile

/** Журнал копится в файле до [MAX_BYTES]; при переполнении отбрасываются самые старые строки (остаётся около [KEEP_BYTES]). */
object LogTrim {
    const val MAX_BYTES = 5L * 1024 * 1024
    const val KEEP_BYTES = 4L * 1024 * 1024

    /** Байты хвоста файла, начиная с целой строки (обрезанная первая строка отбрасывается). */
    fun tailBytes(file: File, bytes: Long): ByteArray {
        if (!file.exists()) return ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = maxOf(0L, len - bytes)
            val buf = ByteArray((len - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            if (start == 0L) return buf
            val nl = buf.indexOf('\n'.code.toByte())
            return if (nl < 0) ByteArray(0) else buf.copyOfRange(nl + 1, buf.size)
        }
    }

    /** Последние строки файла (не больше [maxBytes] с конца), старые сверху. */
    fun tailLines(file: File, maxBytes: Long = MAX_BYTES): List<String> =
        String(tailBytes(file, maxBytes), Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()

    /** Если файл больше [max] — оставить только последние [keep] байт (по границе строки). */
    fun trimIfNeeded(file: File, max: Long = MAX_BYTES, keep: Long = KEEP_BYTES): Boolean {
        if (!file.exists() || file.length() <= max) return false
        val tail = tailBytes(file, keep)
        file.writeBytesAtomic(tail)
        return true
    }
}
