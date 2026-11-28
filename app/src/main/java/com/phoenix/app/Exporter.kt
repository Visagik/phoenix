package com.phoenix.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast

object Exporter {
    private const val MAX_CLIP_CHARS = 400_000
    private val V2RAYNG_PACKAGES = listOf("com.v2ray.ang", "com.v2ray.ang.fdroid")

    fun copy(context: Context, text: String, count: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Phoenix configs", text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // ссылки содержат ключи доступа: не показывать их в плашке буфера обмена
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        // системный буфер ограничен примерно мегабайтом: на 1400 ссылках запись падала с исключением и роняла приложение (находка Фейбла по оболочке №2)
        if (text.length > MAX_CLIP_CHARS) {
            Toast.makeText(context, tr("Слишком много для буфера обмена ({}). Используйте «В файл»", count), Toast.LENGTH_LONG).show()
            return
        }
        try {
            cm.setPrimaryClip(clip)
            Toast.makeText(context, tr("Скопировано: {}. В V2RayNG: «+» → «Импорт из буфера обмена»", count), Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            Toast.makeText(context, tr("Не удалось скопировать: слишком много данных. Используйте «В файл»"), Toast.LENGTH_LONG).show()
        }
    }

    fun openV2RayNG(context: Context) {
        val intent = V2RAYNG_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            Toast.makeText(context, tr("V2RayNG не найден"), Toast.LENGTH_SHORT).show()
        } else {
            context.startActivity(intent)
        }
    }

    /**
     * До Android 10 (API 29) MediaStore.Downloads нет: пишем в папку приложения «Download/Phoenix» (разрешение не нужно; файл исчезает
     * вместе с приложением). Путь для сообщения «Сохранено: …» даёт [where].
     */
    private fun legacyDir(context: Context): java.io.File =
        java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Phoenix").also { it.mkdirs() }

    /** Куда сохраняются файлы: понятный путь для сообщения пользователю. */
    fun where(context: Context): String {
        if (android.os.Build.VERSION.SDK_INT >= 29) return tr("Загрузки/Phoenix")
        val path = legacyDir(context).absolutePath
        val k = path.indexOf("/Android/")
        return if (k >= 0) path.substring(k + 1) else path
    }

    /** Копирует файл в Загрузки/Phoenix/<fileName> (виден после удаления приложения). */
    fun saveFileToDownloads(context: Context, fileName: String, mime: String, source: java.io.File): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return try { source.copyTo(java.io.File(legacyDir(context), fileName), overwrite = true); true } catch (_: Exception) { false }
        return try {
            val resolver = context.contentResolver
            val relPath = Environment.DIRECTORY_DOWNLOADS + "/Phoenix"
            try {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(fileName, "$relPath/"),
                )
            } catch (_: SecurityException) {
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            val stream = resolver.openOutputStream(uri) ?: return false
            stream.use { os -> source.inputStream().use { it.copyTo(os) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Сохраняет в Загрузки/Phoenix/<fileName>. Старый файл с тем же именем заменяется. */
    fun saveToDownloads(context: Context, fileName: String, text: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return try { java.io.File(legacyDir(context), fileName).writeText(text, Charsets.UTF_8); true } catch (_: Exception) { false }
        return try {
            val resolver = context.contentResolver
            val relPath = Environment.DIRECTORY_DOWNLOADS + "/Phoenix"
            // Старый файл удаляем, только если он наш. После переустановки приложения
            // чужой файл удалить нельзя (SecurityException) — тогда система сама добавит "(1)".
            try {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(fileName, "$relPath/"),
                )
            } catch (_: SecurityException) {
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            val stream = resolver.openOutputStream(uri) ?: return false
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
