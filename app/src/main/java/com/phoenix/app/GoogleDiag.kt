package com.phoenix.app

/**
 * Подробный журнал для сервисов Google с проверкой региона (Gemini, NotebookLM): пока такое приложение или сайт открыто,
 * раз в [PERIOD_MS] через текущий сервер проверяются узлы, от которых зависит работа (сам сервис, вход в аккаунт, проверка связи Google),
 * и страна выхода. Строки `DIAG:` в журнале показывают, на каком шаге и на каком сервере ломается (журнал 06.11: проверка Gemini давала 302 и ничего не показывала).
 */
object GoogleDiag {
    const val PERIOD_MS = 120_000L

    private val packages = setOf("com.google.android.apps.bard", "com.google.android.apps.labs.language.tailwind", "com.google.android.googlequicksearchbox")
    private val sites = listOf("gemini.google.com", "bard.google.com", "notebooklm.google.com", "notebook.google", "aistudio.google.com")

    /** Узлы, которые обходятся при проверке: (метка, адрес). */
    val urls: List<Pair<String, String>> = listOf(
        "gemini" to "https://gemini.google.com/app",
        "notebook" to "https://notebooklm.google.com/",
        "вход" to "https://accounts.google.com/",
        "связь" to "https://www.google.com/generate_204",
        "api" to "https://www.googleapis.com/generate_204",
    )

    /** Какой из проверяемых узлов отвечает за открытое сейчас: «notebook» или «gemini». */
    fun labelFor(pkg: String?, site: String?): String =
        if (pkg == "com.google.android.apps.labs.language.tailwind" || (site != null && (site.contains("notebook")))) "notebook" else "gemini"

    private val hintKeys = setOf("location", "region", "country", "reason", "error", "gl", "hl")

    /** Куда привело перенаправление, без секретов: узел, первый отрезок пути и безопасные параметры (location=unsupported и подобные). */
    fun finalHint(finalUrl: String): String {
        val u = try { java.net.URI(finalUrl) } catch (_: Exception) { return "" }
        val host = u.host ?: return ""
        val seg = u.path.orEmpty().trim('/').substringBefore('/').take(20)
        val q = u.rawQuery.orEmpty().split('&').mapNotNull { p ->
            val k = p.substringBefore('=').lowercase()
            if (k in hintKeys) k + "=" + p.substringAfter('=', "").take(24) else null
        }.joinToString("&")
        return host + (if (seg.isNotEmpty()) " /$seg" else "") + (if (q.isNotEmpty()) " ?$q" else "")
    }

    fun relevant(pkg: String?, site: String?): Boolean =
        (pkg != null && pkg in packages) || (site != null && sites.any { site == it || site.endsWith(".$it") })

    /** true — пора делать очередную проверку. */
    fun due(lastAt: Long, now: Long): Boolean = now - lastAt >= PERIOD_MS || now < lastAt

    /** Одна строка журнала по одному узлу. [hops] — цепочка узлов после перенаправлений, [blocked] — признаки заглушки региона. */
    fun line(label: String, code: Int, ms: Long, hops: List<String>, blocked: Boolean, error: String?, snippet: String = ""): String {
        if (error != null) return "$label: ошибка за $ms мс — $error"
        val chain = if (hops.size > 1) ", цепочка ${hops.joinToString(" → ")}" else ""
        return "$label: код $code за $ms мс$chain" + (if (blocked) ", СТРАНИЦА «НЕ ПОДДЕРЖИВАЕТСЯ В СТРАНЕ»" else "") + (if (snippet.isNotBlank()) "; текст: $snippet" else "")
    }

    /** Слова вокруг «поддерживается» на странице: для сервисов, где заглушка не находится по адресу (Gemini отвечает 200 на любой адрес). */
    private val hint = Regex("(?i)(isn['’]t|not|un)\\s*(currently\\s*)?(supported|available)|supported[_ -]countries|не\\s+поддержива|недоступ")

    fun hintsIn(body: String): String =
        hint.findAll(body).take(3).joinToString(" | ") { m -> body.substring(maxOf(0, m.range.first - 40), minOf(body.length, m.range.last + 60)).replace(Regex("\\s+"), " ").trim() }
}


/**
 * Ограничитель автоматической смены сервера по итогам проверки Google: не больше [max] смен за [windowMs],
 * иначе при отсутствии подходящих выходов Phoenix перебирал бы серверы без конца (журнал 07.11: смена каждые 10–15 с, а сервис всё равно закрыт).
 */
class AutoVetoGate(private val max: Int = 3, private val windowMs: Long = 30 * 60_000L) {
    private val times = ArrayList<Long>()

    @Synchronized fun allow(now: Long): Boolean {
        times.removeAll { now - it > windowMs || it > now }
        if (times.size >= max) return false
        times += now
        return true
    }

    @Synchronized fun reset() { times.clear() }
}
