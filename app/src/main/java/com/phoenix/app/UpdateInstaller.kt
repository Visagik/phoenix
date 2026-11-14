package com.phoenix.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Как идёт скачивание и установка новой версии (по кнопке пользователя). */
data class InstallProgress(val stage: Stage = Stage.IDLE, val percent: Int = 0, val error: String = "") {
    enum class Stage { IDLE, DOWNLOADING, VERIFYING, INSTALLING, NEED_PERMISSION, FAILED }
}

/**
 * Скачивание и установка новой версии по выбору пользователя. Берётся только файл из релиза на GitHub; перед установкой проверяются
 * контрольная сумма (если GitHub её отдал), имя пакета и номер версии. Подпись проверяет сам Android: обновление, подписанное другим ключом,
 * не встанет поверх этого приложения.
 */
object UpdateInstaller {
    private const val MAX_BYTES = 200L * 1024 * 1024
    const val ACTION_RESULT = "com.phoenix.app.INSTALL_RESULT"

    /** Итог от приёмника системного установщика (отмена, ошибка): экран подхватывает его и снова включает кнопку. */
    val external = kotlinx.coroutines.flow.MutableStateFlow(InstallProgress())

    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Открыть системное окно «Установка из этого источника», чтобы пользователь разрешил установку из Phoenix. */
    fun openPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    /** Скачать, проверить и передать системе на установку. Блокирующий: вызывать не с главного потока. */
    fun downloadAndInstall(context: Context, onProgress: (InstallProgress) -> Unit) {
        val settings = AppSettings(context)
        val url = settings.updateApkUrl
        fun fail(msg: String) { onProgress(InstallProgress(InstallProgress.Stage.FAILED, error = msg)); try { Storage(context).log("UPDATE: $msg") } catch (_: Throwable) { } }
        if (url.isEmpty() || !UpdateCheck.trustedDownloadUrl(url)) return fail("в релизе нет файла APK")
        // без контрольной суммы устанавливать нельзя: ответ GitHub могли подменить
        if (settings.updateApkSha.isEmpty()) return fail("в релизе нет контрольной суммы — скачайте файл со страницы релиза")
        if (!canInstall(context)) { onProgress(InstallProgress(InstallProgress.Stage.NEED_PERMISSION)); return }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        if (dir.usableSpace < UpdateCheck.neededBytes(settings.updateApkSize)) return fail("не хватает свободного места (нужно около ${UpdateCheck.neededBytes(settings.updateApkSize) / 1_000_000} МБ)")
        val file = File(dir, "phoenix-update.apk")
        try {
            onProgress(InstallProgress(InstallProgress.Stage.DOWNLOADING, 0))
            val digest = MessageDigest.getInstance("SHA-256")
            download(context, url, file, settings.updateApkSize, digest) { onProgress(InstallProgress(InstallProgress.Stage.DOWNLOADING, it)) }
            onProgress(InstallProgress(InstallProgress.Stage.VERIFYING))
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!UpdateCheck.digestMatches(settings.updateApkSha, actual)) { file.delete(); return fail("контрольная сумма скачанного файла не совпала") }
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (info == null || info.packageName != context.packageName) { file.delete(); return fail("скачанный файл — не Phoenix") }
            val have = context.packageManager.getPackageInfo(context.packageName, 0)
            val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            val haveCode = if (Build.VERSION.SDK_INT >= 28) have.longVersionCode else @Suppress("DEPRECATION") have.versionCode.toLong()
            if (newCode <= haveCode) { file.delete(); return fail("в скачанном файле версия не новее установленной") }
            onProgress(InstallProgress(InstallProgress.Stage.INSTALLING))
            install(context, file)
        } catch (e: Exception) {
            file.delete()
            fail("не удалось скачать: " + (e.message ?: e.javaClass.simpleName).take(120))
        }
    }

    private fun download(context: Context, url: String, out: File, expectedSize: Long, digest: MessageDigest, progress: (Int) -> Unit) {
        var current = url
        var hops = 0
        val ua = "Phoenix/" + UpdateChecker.currentVersion(context)
        while (true) {
            if (!UpdateCheck.trustedDownloadUrl(current)) throw java.io.IOException("адрес загрузки не на GitHub")
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", ua)
            try {
                val code = c.responseCode
                if (code in 301..308 && code != 304 && hops < 5) {
                    current = URL(URL(current), c.getHeaderField("Location") ?: throw java.io.IOException("перенаправление без адреса")).toString()
                    hops++
                    continue
                }
                if (code != 200) throw java.io.IOException("ответ $code")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                if (total > MAX_BYTES) throw java.io.IOException("файл слишком большой")
                var done = 0L
                var last = -1
                c.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done > MAX_BYTES) throw java.io.IOException("файл слишком большой")
                            if (total > 0) { val p = (done * 100 / total).toInt().coerceIn(0, 100); if (p != last) { last = p; progress(p) } }
                        }
                    }
                }
                if (total > 0 && done != total) throw java.io.IOException("файл скачан не полностью")
                return
            } finally {
                c.disconnect()
            }
        }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { ins -> session.openWrite("phoenix.apk", 0, file.length()).use { os -> ins.copyTo(os); session.fsync(os) } }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            session.commit(android.app.PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }
}

/** Ответ установщика Android: при необходимости показывает окно подтверждения, ошибки пишет в журнал и показывает коротким сообщением. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> try { Storage(context).log("UPDATE: новая версия установлена") } catch (_: Throwable) { }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // пользователь закрыл окно подтверждения: кнопка снова доступна
                UpdateInstaller.external.value = InstallProgress()
            }
            else -> {
                UpdateInstaller.external.value = InstallProgress(InstallProgress.Stage.FAILED, error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(160))
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(160)
                try { Storage(context).log("UPDATE: установка не удалась: $msg") } catch (_: Throwable) { }
                Toast.makeText(context, tr("Не удалось установить обновление: {}", msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
