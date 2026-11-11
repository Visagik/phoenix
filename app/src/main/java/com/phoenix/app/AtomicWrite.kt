package com.phoenix.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Запись файла «всё или ничего»: сначала во временный файл рядом, затем fsync и переименование.
 * Если процесс убьют посреди записи (MIUI это любит), останется либо старое содержимое, либо новое целиком —
 * а не половина истории, по которой потом считается надёжность.
 */
object AtomicWrite {
    const val SUFFIX = ".tmp"

    fun write(file: File, bytes: ByteArray) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + "." + System.nanoTime() + SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Остатки временных файлов после оборванной записи: удаляются при старте. Возвращает, сколько убрано. */
    fun cleanup(dir: File): Int {
        var n = 0
        dir.walkTopDown().maxDepth(3).forEach { f -> if (f.isFile && f.name.endsWith(SUFFIX) && f.delete()) n++ }
        return n
    }
}

fun File.writeTextAtomic(text: String) = AtomicWrite.write(this, text.toByteArray(Charsets.UTF_8))

fun File.writeBytesAtomic(bytes: ByteArray) = AtomicWrite.write(this, bytes)
