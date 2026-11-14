package com.phoenix.app

/** Адресная строка браузера → домен. Хранится только домен, без пути и параметров. */
object BrowserUrl {
    /** Домен из текста адресной строки; null, если там не адрес (подсказка «Поиск или адрес», пустое поле, запрос с пробелами). */
    fun host(text: CharSequence?): String? {
        val s = text?.toString()?.trim() ?: return null
        if (s.isEmpty() || s.length > 300 || s.any { it.isWhitespace() }) return null
        return Domains.host(s)
    }
}

/** Идентификаторы адресной строки известных браузеров (подобраны по их приложениям, у новых версий могут меняться — тогда запасной поиск по полю ввода). */
object BrowserBars {
    private val ids: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "com.chrome.canary" to listOf("com.chrome.canary:id/url_bar"),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
        "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.opera.mini.native" to listOf("com.opera.mini.native:id/url_field"),
        "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
        "com.yandex.browser" to listOf("com.yandex.browser:id/bro_omnibar_address_title_text", "com.yandex.browser:id/bro_omnibar_address_title_text_view"),
        "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.firefox_beta" to listOf("org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.fenix" to listOf("org.mozilla.fenix:id/mozac_browser_toolbar_url_view"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
        // у этих идентификатор не известен точно: ищем поле ввода с адресом
        "com.mi.globalbrowser" to emptyList(),
        "com.android.browser" to emptyList(),
        "com.vivo.browser" to emptyList(),
        "com.huawei.browser" to emptyList(),
        "com.UCMobile.intl" to emptyList(),
        "com.ecosia.android" to emptyList(),
    )

    val packages: Set<String> get() = ids.keys
    fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in ids
    fun idsFor(pkg: String): List<String> = ids[pkg].orEmpty()
}

/** Домен сайта, открытого сейчас в браузере (только в памяти, нигде не сохраняется и никуда не отправляется). */
object CurrentSite {
    class Seen(val host: String, val pkg: String, val at: Long)

    @Volatile private var seen: Seen? = null

    fun set(host: String, pkg: String, now: Long) { seen = Seen(host, pkg, now) }
    fun clear() { seen = null }

    /** Последний замеченный сайт, если он не старше [maxAgeMs]. */
    fun fresh(now: Long, maxAgeMs: Long = 10 * 60_000L): Seen? = seen?.takeIf { now >= it.at && now - it.at <= maxAgeMs }

    /** Включена ли служба в списке специальных возможностей (строка настройки ENABLED_ACCESSIBILITY_SERVICES). */
    fun isListed(enabledSetting: String?, pkg: String, cls: String): Boolean {
        if (enabledSetting.isNullOrBlank()) return false
        val short = "$pkg/.$cls"
        val full = "$pkg/$pkg.$cls"
        return enabledSetting.split(':').any { it.equals(short, true) || it.equals(full, true) || it.equals("$pkg/$cls", true) }
    }
}

object SiteMatch {
    /** Цель, к которой относится сайт: своя цель-сайт, иначе приложение, которому сайт принадлежит. */
    fun targetFor(host: String, targets: List<Target>): Target? {
        val h = host.lowercase()
        targets.firstOrNull { it.kind == TargetKind.SITE && AppDomains.covers(it.value.lowercase(), h) }?.let { return it }
        val pkg = AppDomains.packageFor(h) ?: return null
        // приложение может быть добавлено в цели под другим пакетом (Telegram X вместо Telegram): ищем любое с теми же доменами
        val own = AppDomains.hosts(pkg).toSet()
        return targets.firstOrNull { it.kind == TargetKind.APP && (it.value == pkg || AppDomains.hosts(it.value).toSet() == own) }
    }
}

/** Недавние приложения по событиям «вышло на передний план» (пакет, время). */
object RecentApps {
    fun pick(events: List<Pair<String, Long>>, exclude: Set<String>, now: Long, windowMs: Long, limit: Int = 4): List<String> {
        val out = LinkedHashSet<String>()
        for ((pkg, at) in events.sortedByDescending { it.second }) {
            if (now - at > windowMs || at > now) continue
            if (pkg in exclude || pkg.contains(".inputmethod") || pkg == "android") continue
            out += pkg
            if (out.size >= limit) break
        }
        return out.toList()
    }
}

/** Порядок серверов под открытое приложение/сайт с учётом страны выхода. */
object AppFit {
    /** Сервис закрыт из страны выхода [cc] (по таблице стран и по списку сервисов с проверкой региона). */
    fun exitBlocked(checkUrl: String, service: CheckService?, cc: String?): Boolean {
        if (cc.isNullOrEmpty()) return false
        if (GeoGate.blocks(checkUrl, cc)) return true
        val i = if (service != null) ServiceBlocks.LABELS.indexOf(service.id) else -1
        return i >= 0 && ServiceBlocks.status(cc.uppercase())[i] == "blocked"
    }

    /**
     * Ступени: 0 — серверы самой цели, 1 — серверы других недавних приложений, 2 — остальные,
     * 3 — страна по названию закрыта для сервиса, замера нет (у «🇷🇺 LTE …» выход бывает за границей), 4 — замер показал закрытую страну выхода,
     * 5 — этот сервер уже отмечен как закрытый именно для этого сайта (DIAG/кнопка «не открывается»), независимо от страны выхода.
     * Порядок внутри ступени сохраняется. Ступень 5 — самая низкая: такой сервер не должен быть выбран повторно сразу после того,
     * как его для этого же сайта только что исключили (иначе отметка в ServiceVeto ни на что не влияет).
     */
    fun tier(c: Candidate, primary: Set<String>, others: Set<String>, measured: String?, blocked: (String?) -> Boolean, vetoed: (Candidate) -> Boolean = { false }): Int = when {
        vetoed(c) -> 5
        blocked(measured) -> 4
        c.identity in primary -> 0
        c.identity in others -> 1
        measured == null && blocked(c.cc.ifEmpty { null }) -> 3
        else -> 2
    }

    fun arrange(list: List<Candidate>, primary: Set<String>, others: Set<String>, measuredOf: (Candidate) -> String?, blocked: (String?) -> Boolean, vetoed: (Candidate) -> Boolean = { false }): List<Candidate> =
        list.sortedBy { tier(it, primary, others, measuredOf(it), blocked, vetoed) }
}

/** Цели без серверов не перебираются глубоко при каждом прогоне: после полного провала пауза. */
object MissBackoff {
    const val TTL_MS = 6 * 60 * 60_000L

    fun parse(text: String): Map<String, Long> = text.lines().mapNotNull { l ->
        val p = l.split('\t')
        val t = p.getOrNull(1)?.toLongOrNull()
        if (p.size >= 2 && p[0].isNotEmpty() && t != null) p[0] to t else null
    }.toMap()

    fun render(map: Map<String, Long>): String = map.entries.joinToString("\n") { "${it.key}\t${it.value}" } + if (map.isEmpty()) "" else "\n"

    fun skip(map: Map<String, Long>, id: String, now: Long): Boolean = map[id]?.let { now >= it && now - it < TTL_MS } ?: false
}
