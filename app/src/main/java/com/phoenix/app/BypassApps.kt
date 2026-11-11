package com.phoenix.app

import java.io.File

/** Готовый набор российских приложений, которые лучше пускать мимо VPN (банки, госуслуги, маркетплейсы…). */
object RussianApps {
    /** Установленные приложения, которые по названию пакета похожи на российские и ещё не в наборе. */
    fun discover(installed: Collection<String>): List<String> {
        val known = PACKAGES.toSet()
        return installed.filter { p ->
            p !in known && !p.startsWith("com.phoenix") && !Regex("(?i)vpn|v2ray|xray|proxy|tunnel|byedpi|zapret").containsMatchIn(p) &&
                (p.startsWith("ru.") || p.startsWith("com.yandex.") || p.startsWith("com.vkontakte") || p.startsWith("com.mail.") || p.startsWith("ru.mail"))
        }.sorted()
    }

    val PACKAGES: List<String> = listOf(
        // банки и платежи
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews",
        "ru.rosbank.android", "ru.nspk.mirpay", "ru.nspk.sbpay",
        // государство
        "ru.rostel", "com.gnivts.selfemployed", "ru.russianpost.mobileapp",
        // соцсети и мессенджеры
        "com.vkontakte.android", "ru.ok.android", "ru.oneme.app",
        // Яндекс
        "ru.yandex.searchplugin", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.taxi",
        "ru.yandex.eda", "ru.yandex.mail", "ru.yandex.disk", "ru.yandex.music", "com.yandex.browser",
        "ru.beru.android", "ru.yandex.shell",
        // покупки и сервисы
        "com.avito.android", "ru.ozon.app.android", "com.wildberries.ru", "ru.tander.magnit",
        "ru.dublgis.dgismobile",
        // операторы
        "ru.mts.mymts", "ru.beeline.services", "ru.megafon.mlk", "ru.tele2.mytele2", "ru.yota.android",
        // транспорт
        "ru.rzd.pass", "ru.aeroflot",
        // видео
        "ru.kinopoisk", "ru.more.play", "ru.ivi.client", "ru.rt.video.app.mobile", "ru.rutube.app",
    )
}

/**
 * Зарубежные сервисы, которые в России НЕ заблокированы и в обычном режиме (чёрные списки) спокойно открываются напрямую.
 * Через VPN они не нужны: напрямую быстрее, меньше трафика и батареи. Список консервативный: всё, что режется
 * (Telegram, YouTube, Instagram, Facebook, X, Discord, ChatGPT, Claude и т. п.), сюда не входит и идёт через VPN.
 * Включается в режиме чёрных списков; отключить можно в настройках.
 */
object OpenServices {
    val DOMAINS: List<String> = listOf(
        // игры и разработка
        "domain:steampowered.com", "domain:steamcommunity.com", "domain:steamstatic.com", "domain:steamcontent.com",
        "domain:github.com", "domain:githubusercontent.com", "domain:githubassets.com", "domain:gitlab.com",
        "domain:stackoverflow.com", "domain:stackexchange.com",
        // справочники и сервисы
        "domain:wikipedia.org", "domain:wikimedia.org", "domain:wiktionary.org",
        "domain:microsoft.com", "domain:live.com", "domain:office.com", "domain:windowsupdate.com", "domain:msftconnecttest.com",
        "domain:apple.com", "domain:icloud.com", "domain:mzstatic.com",
        "domain:amazon.com", "domain:amazonaws.com", "domain:aliexpress.com", "domain:alicdn.com",
        "domain:zoom.us", "domain:twitch.tv", "domain:ttvnw.net",
        "domain:cloudflare.com", "domain:mozilla.org", "domain:mozilla.net",
    )

    val PACKAGES: List<String> = listOf(
        "com.valvesoftware.android.steam.community", "com.valvesoftware.steamlink", "com.github.android", "org.wikipedia",
        "us.zoom.videomeetings", "tv.twitch.android.app", "com.aliexpress.android",
        "com.amazon.mShop.android.shopping", "com.microsoft.office.outlook",
    )
}

/**
 * Списки обхода VPN: свои приложения пользователя + готовый российский набор (его можно выключить).
 * Лежит рядом с остальными данными режима, поэтому у каждого режима свой список.
 */
class BypassStore(private val dir: File, private val mode: Mode? = null, private val network: String = "") {
    /** Самообучение «что открывается напрямую в этой сети» (см. [DirectLearn]). */
    val learnFile = File(dir, "direct_learn.tsv")
    private val appsFile = File(dir, "bypass_apps.txt")
    private val presetOffFile = File(dir, "bypass_preset_off")
    private val sitesFile = File(dir, "bypass_sites.txt")
    private val openFile = File(dir, "bypass_open.txt")

    /** Счётчики проверок «открывается напрямую» (см. DirectWatch). */
    val watchFile = File(dir, "direct_watch.tsv")

    /** Найденные автоматически приложения, которые пользователь убрал из обхода. */
    private val autoOffFile = File(dir, "bypass_auto_off.txt")

    fun autoOff(): Set<String> =
        if (autoOffFile.exists()) autoOffFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()

    fun excludeAuto(pkg: String) {
        dir.mkdirs()
        autoOffFile.writeTextAtomic((autoOff() + pkg).sorted().joinToString("\n") + "\n")
    }

    /** Российские приложения, найденные на телефоне автоматически (без убранных пользователем). */
    fun discovered(installed: Collection<String>): List<String> =
        if (presetEnabled && !russianDown()) RussianApps.discover(installed).filter { it !in autoOff() } else emptyList()

    /** В этой сети ни один российский сервис из каталога напрямую не открывается — найденные приложения без проверки в обход не берём. */
    private fun russianDown(): Boolean =
        network.isNotEmpty() && DirectLearn.groupDown(DirectLearn.load(learnFile), network, DirectCatalog.ENTRIES.filter { it.russian }.map { it.id })

    /** Сервисы, которые в этой сети напрямую не открываются (идут через VPN): по сети, если она известна, иначе по общему старому счётчику. */
    private fun blockedIds(): Set<String> =
        if (network.isNotEmpty()) DirectLearn.blockedIds(DirectLearn.load(learnFile), network)
        else DirectWatch.blockedIds(DirectWatch.load(watchFile))

    fun userApps(): Set<String> =
        if (appsFile.exists()) appsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet() else emptySet()

    fun setUserApps(packages: Collection<String>) {
        dir.mkdirs()
        appsFile.writeTextAtomic(packages.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().joinToString("\n") + "\n")
    }

    fun toggle(pkg: String, enabled: Boolean) {
        val set = userApps().toMutableSet()
        if (enabled) set.add(pkg) else set.remove(pkg)
        setUserApps(set)
    }

    var presetEnabled: Boolean
        get() = !presetOffFile.exists()
        set(v) {
            dir.mkdirs()
            if (v) presetOffFile.delete() else presetOffFile.writeTextAtomic("off")
        }

    /**
     * «Не заблокированное в РФ» идёт напрямую. По умолчанию включено только в режиме чёрных списков
     * (при белых списках напрямую открывается лишь разрешённое, пресет там не нужен).
     */
    var openEnabled: Boolean
        get() {
            val saved = try {
                openFile.takeIf { it.exists() }?.readText()?.trim()
            } catch (_: Exception) {
                null
            }
            return when (saved) {
                "on" -> true
                "off" -> false
                else -> mode == Mode.BLACKLIST
            }
        }
        set(v) {
            dir.mkdirs()
            openFile.writeTextAtomic(if (v) "on" else "off")
        }

    /** Свои сайты в обход VPN (домены). */
    fun userSites(): List<String> =
        if (sitesFile.exists()) sitesFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct() else emptyList()

    /** @return null — добавлено, иначе текст ошибки */
    fun addSite(input: String): String? {
        val host = Domains.host(input) ?: return "Нужен адрес сайта вида example.com"
        val sites = userSites()
        if (host in sites) return "Уже в списке"
        dir.mkdirs()
        sitesFile.writeTextAtomic((sites + host).joinToString("\n") + "\n")
        return null
    }

    fun removeSite(host: String) {
        dir.mkdirs()
        sitesFile.writeTextAtomic(userSites().filter { it != host }.joinToString("\n") + "\n")
    }

    private val appLinksOffFile = File(dir, "bypass_app_links_off")

    /** Сайты выбранных для обхода приложений (по их объявленным ссылкам) тоже идут мимо VPN. По умолчанию включено. */
    var appLinksEnabled: Boolean
        get() = !appLinksOffFile.exists()
        set(v) {
            dir.mkdirs()
            if (v) appLinksOffFile.delete() else appLinksOffFile.writeTextAtomic("off")
        }

    /**
     * Правила Xray для сайтов, идущих напрямую: российские по умолчанию (если включено) + свои + сайты приложений из обхода
     * ([appDomains] — готовые правила от [AppLinks]; учитываются, если включено [appLinksEnabled]).
     */
    fun siteRules(appDomains: List<String> = emptyList()): List<String> {
        val off = DirectWatch.excludedDomains(blockedIds())
        val base = ((if (presetEnabled) RussianSites.DOMAINS else emptyList()) +
            (if (openEnabled) OpenServices.DOMAINS else emptyList())).filter { it !in off } + userSites().map { Domains.rule(it) }
        return if (appLinksEnabled) (base + appDomains.filter { it !in off }).distinct() else base
    }

    /** Итоговый набор пакетов, которые не идут через VPN. */
    fun effective(installed: Collection<String> = emptyList()): Set<String> {
        val off = DirectWatch.excludedPackages(blockedIds())
        val auto = discovered(installed)
        return ((if (presetEnabled) RussianApps.PACKAGES else emptyList()) + auto +
            (if (openEnabled) OpenServices.PACKAGES else emptyList())).filter { it !in off }.toSet() + userApps()
    }
}
