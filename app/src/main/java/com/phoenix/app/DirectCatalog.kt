package com.phoenix.app

import java.io.File

/** Сервис из списка обхода VPN: по какому адресу проверить, что он открывается напрямую, и что к нему относится. */
data class DirectEntry(
    val id: String,
    val url: String,
    val domains: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    /** true — российский (набор «российские приложения и сайты»), false — зарубежный «не заблокированный». */
    val russian: Boolean,
)

object DirectCatalog {
    val ENTRIES: List<DirectEntry> = listOf(
        // российские
        DirectEntry("sber", "https://www.sberbank.ru/", listOf("domain:sberbank.com"), listOf("ru.sberbankmobile", "ru.nspk.sbpay"), true),
        DirectEntry("tinkoff", "https://www.tbank.ru/", packages = listOf("com.idamob.tinkoff.android"), russian = true),
        DirectEntry("vtb", "https://www.vtb.ru/", packages = listOf("ru.vtb24.mobilebanking.android"), russian = true),
        DirectEntry("alfa", "https://alfabank.ru/", packages = listOf("ru.alfabank.mobile.android"), russian = true),
        DirectEntry("gosuslugi", "https://www.gosuslugi.ru/", packages = listOf("ru.rostel"), russian = true),
        DirectEntry("vk", "https://vk.com/", listOf("domain:vk.com", "domain:vk-cdn.net", "domain:userapi.com", "domain:vkuseraudio.net", "domain:vkuser.net"), listOf("com.vkontakte.android", "ru.oneme.app"), true),
        DirectEntry("ok", "https://ok.ru/", listOf("domain:mycdn.me"), listOf("ru.ok.android"), true),
        DirectEntry("yandex", "https://ya.ru/", listOf("domain:yandex.com", "domain:yandex.net", "domain:yastatic.net", "domain:ya.cc"),
            listOf("ru.yandex.searchplugin", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.taxi", "ru.yandex.eda", "ru.yandex.mail", "ru.yandex.disk", "ru.yandex.music", "com.yandex.browser", "ru.beru.android", "ru.yandex.shell"), true),
        DirectEntry("2gis", "https://2gis.ru/", listOf("domain:2gis.com"), listOf("ru.dublgis.dgismobile"), true),
        DirectEntry("avito", "https://www.avito.ru/", listOf("domain:avito.st"), listOf("com.avito.android"), true),
        DirectEntry("ozon", "https://www.ozon.ru/", packages = listOf("ru.ozon.app.android"), russian = true),
        DirectEntry("wb", "https://www.wildberries.ru/", listOf("domain:wbstatic.net"), listOf("com.wildberries.ru"), true),
        DirectEntry("kinopoisk", "https://www.kinopoisk.ru/", packages = listOf("ru.kinopoisk"), russian = true),
        DirectEntry("rutube", "https://rutube.ru/", packages = listOf("ru.rutube.app"), russian = true),
        DirectEntry("mts", "https://www.mts.ru/", packages = listOf("ru.mts.mymts"), russian = true),
        // зарубежные, не заблокированные
        DirectEntry("steam", "https://store.steampowered.com/", listOf("domain:steampowered.com", "domain:steamcommunity.com", "domain:steamstatic.com", "domain:steamcontent.com"), listOf("com.valvesoftware.android.steam.community", "com.valvesoftware.steamlink"), false),
        DirectEntry("github", "https://github.com/", listOf("domain:github.com", "domain:githubusercontent.com", "domain:githubassets.com"), listOf("com.github.android"), false),
        DirectEntry("wikipedia", "https://www.wikipedia.org/", listOf("domain:wikipedia.org", "domain:wikimedia.org", "domain:wiktionary.org"), listOf("org.wikipedia"), false),
        DirectEntry("microsoft", "https://www.msftconnecttest.com/connecttest.txt", listOf("domain:microsoft.com", "domain:live.com", "domain:office.com", "domain:windowsupdate.com", "domain:msftconnecttest.com"), listOf("com.microsoft.office.outlook"), false),
        DirectEntry("apple", "https://captive.apple.com/", listOf("domain:apple.com", "domain:icloud.com", "domain:mzstatic.com"), russian = false),
        DirectEntry("amazon", "https://www.amazon.com/", listOf("domain:amazon.com", "domain:amazonaws.com"), listOf("com.amazon.mShop.android.shopping"), false),
        DirectEntry("aliexpress", "https://www.aliexpress.com/", listOf("domain:aliexpress.com", "domain:alicdn.com"), listOf("com.aliexpress.android"), false),
        DirectEntry("zoom", "https://zoom.us/", listOf("domain:zoom.us"), listOf("us.zoom.videomeetings"), false),
        DirectEntry("twitch", "https://www.twitch.tv/", listOf("domain:twitch.tv", "domain:ttvnw.net"), listOf("tv.twitch.android.app"), false),
    )
}

/** Счётчики подряд идущих проверок «открылось / не открылось» по сервису. */
data class DirectStat(val okRun: Int = 0, val failRun: Int = 0) {
    /** Напрямую не открывается (два раза подряд): сервис идёт через VPN. */
    val blocked: Boolean get() = failRun >= FAILS_TO_BLOCK

    fun next(ok: Boolean): DirectStat = if (ok) DirectStat(okRun + 1, 0) else DirectStat(0, failRun + 1)

    companion object {
        const val FAILS_TO_BLOCK = 2
    }
}

/**
 * Список обхода сам следит за собой: сервисы из готовых наборов проверяются напрямую (приложение всегда мимо своего VPN),
 * и те, что перестали открываться, временно уходят через VPN, а как только откроются — возвращаются в обход.
 * В белых списках так же отсеивается то, что не открывается напрямую; найденные на телефоне российские приложения добавляются.
 */
object DirectWatch {
    fun load(file: File): Map<String, DirectStat> {
        if (!file.exists()) return emptyMap()
        val out = LinkedHashMap<String, DirectStat>()
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size < 3) continue
            out[p[0]] = DirectStat(p[1].toIntOrNull() ?: continue, p[2].toIntOrNull() ?: continue)
        }
        return out
    }

    fun save(file: File, stats: Map<String, DirectStat>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomic(stats.entries.joinToString("") { (k, v) -> "$k\t${v.okRun}\t${v.failRun}\n" })
    }

    fun blockedIds(stats: Map<String, DirectStat>): Set<String> = stats.filterValues { it.blocked }.keys

    /** Домены правил, которые не нужно пускать напрямую. */
    fun excludedDomains(blocked: Set<String>): Set<String> =
        DirectCatalog.ENTRIES.filter { it.id in blocked }.flatMap { it.domains }.toSet()

    fun excludedPackages(blocked: Set<String>): Set<String> =
        DirectCatalog.ENTRIES.filter { it.id in blocked }.flatMap { it.packages }.toSet()
}
