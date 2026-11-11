package com.phoenix.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast

object Exporter {
    private val V2RAYNG_PACKAGES = listOf("com.v2ray.ang", "com.v2ray.ang.fdroid")

    fun copy(context: Context, text: String, count: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Phoenix configs", text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // ссылки содержат ключи доступа: не показывать их в плашке буфера обмена
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(context, tr("Скопировано: {}. В V2RayNG: «+» → «Импорт из буфера обмена»", count), Toast.LENGTH_LONG).show()
    }

    fun openV2RayNG(context: Context) {
        val intent = V2RAYNG_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            Toast.makeText(context, tr("V2RayNG не найден"), Toast.LENGTH_SHORT).show()
        } else {
            context.startActivity(intent)
        }
    }

    /** Сохраняет в Загрузки/Phoenix/<fileName>. Старый файл с тем же именем заменяется. */
    fun saveToDownloads(context: Context, fileName: String, text: String): Boolean {
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
