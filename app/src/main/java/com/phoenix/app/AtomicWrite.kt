package com.phoenix.app

import java.io.File
import java.io.FileOutputStream

/**
 * Запись файла «всё или ничего»: сначала во временный файл рядом, затем fsync и переименование.
 * Если процесс убьют посреди записи (MIUI это любит), останется либо старое содержимое, либо новое целиком —
 * а не половина истории, по которой потом считается надёжность.
 */
object AtomicWrite {
    const val SUFFIX = ".tmp"

    /** java.nio.file.Files есть только с Android 8 (API 26); на старых версиях замена идёт через renameTo (на одной файловой системе она атомарна). */
    fun write(file: File, bytes: ByteArray, useNio: Boolean = android.os.Build.VERSION.SDK_INT >= 26) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + "." + System.nanoTime() + SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
            if (useNio && android.os.Build.VERSION.SDK_INT >= 26) moveNio(tmp, file) else if (!tmp.renameTo(file)) throw java.io.IOException("не удалось заменить файл ${file.name}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // java.nio.file — полными именами и в отдельной функции: на Android 6 класс не загружается, пока эту ветку не вызвали
    @androidx.annotation.RequiresApi(26)
    private fun moveNio(tmp: File, file: File) {
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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
