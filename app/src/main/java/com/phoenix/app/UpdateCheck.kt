package com.phoenix.app

/** Описание последнего релиза на GitHub (то, что нужно для плашки и уведомления). */
data class ReleaseInfo(val tag: String, val url: String, val apkUrl: String, val notes: String, val apkSha256: String = "", val apkSize: Long = 0L, val signingSha256: String = "")

/**
 * Проверка новой версии приложения: чистая часть (разбор ответа GitHub и сравнение версий).
 * Сеть, уведомление и расписание — в [UpdateChecker].
 */
object UpdateCheck {
    const val API_URL = "https://api.github.com/repos/Visagik/phoenix/releases/latest"
    const val PAGE_URL = "https://github.com/Visagik/phoenix/releases/latest"

    private fun str(json: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return unescape(m.groupValues[1])
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}
                    'u' -> if (i + 5 < s.length) { s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }; i += 4 }
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Ответ `releases/latest`; null — не похоже на релиз. Черновики и предрелизы API туда не отдаёт. */
    fun parse(json: String): ReleaseInfo? {
        val tag = str(json, "tag_name")?.trim().orEmpty()
        if (tag.isEmpty() || tag.length > 40) return null
        val url = str(json, "html_url")?.takeIf { it.startsWith("https://github.com/") } ?: PAGE_URL
        val apkMatch = Regex("\"browser_download_url\"\\s*:\\s*\"(https://[^\"]+?\\.apk)\"").find(json)
        val apk = apkMatch?.groupValues?.get(1).orEmpty()
        // в описании файла контрольная сумма и размер идут ДО ссылки; берём то, что между предыдущей ссылкой (или началом) и этой
        var sha = ""
        var size = 0L
        if (apkMatch != null) {
            val before = json.substring(0, apkMatch.range.first)
            val own = before.substring(before.lastIndexOf("\"browser_download_url\"").coerceAtLeast(0))
            sha = Regex("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"").findAll(own).lastOrNull()?.groupValues?.get(1)?.lowercase().orEmpty()
            size = Regex("\"size\"\\s*:\\s*(\\d{1,12})").findAll(own).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
        val body = str(json, "body") ?: ""
        return ReleaseInfo(tag, url, apk, body.take(600), sha, size, signingFromNotes(body))
    }

    /**
     * Отпечаток ключа подписи новой версии из описания релиза: строка «Подпись: <sha256>» (или «Signing: …»), хеш из 64 символов, можно с двоеточиями.
     * Пусто — в описании не указан (тогда о смене ключа станет известно только после скачивания).
     */
    fun signingFromNotes(body: String): String {
        for (line in body.lines()) {
            val l = line.lowercase()
            if (!(l.contains("подпись") || l.contains("signing") || l.contains("signature"))) continue
            val hex = line.replace(Regex("(?<=[0-9a-fA-F]{2}):(?=[0-9a-fA-F]{2})"), "")
            val m = Regex("(?<![0-9a-zA-Z])[0-9a-fA-F]{64}(?![0-9a-zA-Z])").find(hex) ?: continue
            return m.value.lowercase()
        }
        return ""
    }

    /** «v3.9.64» / «3.9.64-debug» → [3, 9, 64]; пустой список, если чисел нет. */
    fun numbers(version: String): List<Int> =
        Regex("\\d+").findAll(version.removePrefix("v").removePrefix("V").substringBefore('-')).mapNotNull { it.value.toIntOrNull() }.toList()

    /** true, если [remote] строго новее [local]. Нечитаемая версия считается не новее (лучше промолчать, чем звать к ложному обновлению). */
    fun isNewer(remote: String, local: String): Boolean {
        val r = numbers(remote)
        val l = numbers(local)
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Уведомлять о версии [tag], только если о ней ещё не сообщали ([notified] — последняя, о которой сообщили). */
    fun shouldNotify(tag: String, local: String, notified: String): Boolean = isNewer(tag, local) && tag != notified

    /** Файлы релизов этого репозитория лежат только по такому пути. */
    const val RELEASE_PATH = "/Visagik/phoenix/releases/download/"

    /**
     * Скачивать APK можно только из релизов этого репозитория на github.com и с двух узлов хранилища релизов
     * (на них GitHub перенаправляет; путь там не наш, поэтому проверяется только узел). Остальное — отказ.
     */
    fun trustedDownloadUrl(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (u.scheme?.lowercase() != "https") return false
        if (u.rawUserInfo != null) return false
        val h = u.host?.lowercase() ?: return false
        if (h == "objects.githubusercontent.com" || h == "release-assets.githubusercontent.com") return true
        return h == "github.com" && (u.rawPath ?: "").startsWith(RELEASE_PATH)
    }

    /** Контрольная сумма обязательна: без неё ([expected] пустой) устанавливать нельзя — пользователю открывается страница релиза. */
    fun digestMatches(expected: String, actual: String): Boolean = expected.isNotEmpty() && expected.equals(actual, ignoreCase = true)

    /** Сколько места нужно: файл в кэше и его копия в сессии установки, плюс запас. Размер неизвестен — берём с запасом. */
    fun neededBytes(apkSize: Long): Long = (if (apkSize > 0) apkSize else 100_000_000L) * 2 + 20_000_000L
}

/**
 * Когда обновление придётся ставить с удалением приложения: подпись новой версии не совпадает с подписью установленной
 * (Android не ставит такое обновление поверх: данные приложения привязаны к ключу).
 */
object ReinstallPlan {
    /** По описанию релиза, до скачивания: известен отпечаток новой версии и он не совпал ни с одним из ключей установленной. */
    fun neededByRelease(ownCerts: Set<String>, releaseSigning: String): Boolean =
        releaseSigning.isNotEmpty() && ownCerts.isNotEmpty() && ownCerts.none { it.equals(releaseSigning, ignoreCase = true) }

    /** По скачанному файлу (надёжнее описания): ключи файла и установленной версии не пересекаются. */
    fun neededByFile(ownCerts: Set<String>, fileCerts: Set<String>): Boolean =
        ownCerts.isNotEmpty() && fileCerts.isNotEmpty() && ownCerts.none { o -> fileCerts.any { it.equals(o, ignoreCase = true) } }

    /** Имя файла новой версии в Загрузках (виден после удаления приложения). */
    fun apkFileName(tag: String): String = "Phoenix-" + tag.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.replace(Regex("\\.{2,}"), ".").trim('.').ifEmpty { "new" } + ".apk"
}
