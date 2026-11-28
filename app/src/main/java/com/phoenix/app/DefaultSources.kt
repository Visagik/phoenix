package com.phoenix.app

/**
 * Встроенные источники конфигов по режимам: добавляются к общему списку и к своим ссылкам.
 * Любой можно отключить через blacklist источников (вкладка «Источники»).
 */
object DefaultSources {
    private const val IGARECK = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/"
    private const val IGARECK_CODEBERG = "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/"

    val BLACK = listOf(
        IGARECK + "BLACK_SS%2BAll_RUS.txt",
        IGARECK + "BLACK_SS_WEAK_DPI_RUS.txt",
        IGARECK + "BLACK_VLESS_RUS.txt",
        IGARECK + "BLACK_VLESS_RUS_mobile.txt",
        "https://github.com/FLAT447/v2ray-lists/raw/refs/heads/main/BLACK_FULL.txt",
        // общий список с сортировкой по задержке (не российский: хуже по доле рабочих, но расширяет выбор)
        "https://raw.githubusercontent.com/ShatakVPN/ConfigForge-V2Ray/main/configs/vless.txt",
        // иранские и общие сборники (страна с сильными блокировками, Reality/VLESS)
        "https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/refs/heads/main/config/protocols/vless.txt",
        "https://raw.githubusercontent.com/Mohammadgb0078/IRV2ray/main/vless.txt",
        "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/vless",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt",
        // адреса из README авторов; из песочницы не открылись (404/блок прокси), проверяются на телефоне
        "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
        "https://raw.githubusercontent.com/roosterkid/openproxylist/refs/heads/main/V2RAY_RAW.txt",
    )

    val WHITE = listOf(
        // Белые списки от igareck (мобильные сети): 150 лучших для телефона и проверенный набор по CIDR; SNI-подписка ниже (снова не пустая)
        IGARECK + "Vless-Reality-White-Lists-Rus-Mobile.txt",
        IGARECK + "WHITE-CIDR-RU-checked.txt",
        IGARECK + "WHITE-CIDR-RU-all.txt",
        "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/whitelist.txt",
        "https://raw.githubusercontent.com/u23jd9/russia-whitelist-vpn/main/whitelist_raw.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://github.com/FLAT447/v2ray-lists/raw/refs/heads/main/WHITE_FULL.txt",
        "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-all.txt",
        // Самые стабильные по накопленным данным (docs/BASELINE.md): не меньше 80 % удачных загрузок
        "https://raw.githubusercontent.com/flaafix/AetrisVPN/refs/heads/main/AetrisVPN.txt",
        "https://raw.githubusercontent.com/vladvarp/Prometheus/refs/heads/main/WhiteList/vless.txt",
        "https://sub.vlessfo.ru/vlessforu/working_configs.txt",
        "https://etoneya.su/whitelist",
        "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_lite.txt",
        "https://raw.githubusercontent.com/OceaniaVPN/StekloVPN/main/configs/whitelist.txt",
        "https://raw.githubusercontent.com/gooseteam-hackers/GooseVPN/refs/heads/main/configs/balanced.txt",
        "https://raw.githubusercontent.com/gooseteam-hackers/GooseVPN/refs/heads/main/configs/plus.txt",
        "https://vpn.akres.fun/bwl",
        // Зеркала не на GitHub: при белых списках raw.githubusercontent.com часто не открывается. Те же файлы, дубли конфигов отсеиваются при сборе.
        // Адреса со страниц авторов 24.11; из песочницы не проверялись, проверяются на телефоне. Две последние ссылки Codeberg собраны по той же схеме, что и первые две.
        IGARECK_CODEBERG + "Vless-Reality-White-Lists-Rus-Mobile.txt",
        IGARECK_CODEBERG + "WHITE-CIDR-RU-checked.txt",
        IGARECK_CODEBERG + "WHITE-CIDR-RU-all.txt",
        "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
        "https://gitlab.com/zieng2/wl/-/raw/main/vless_universal.txt",
        "https://cdn.jsdelivr.net/gh/solovyov-jenya2004/all_subs@main/final_sorted",
        "https://gitlab.com/solovyov-jenya2004/all_subs/-/raw/main/final_sorted/",
        "https://raw.githubusercontent.com/MrEndi777709/Endi-VPN/main/mrendi-vpn-white-list.txt",
        // igareck SNI-подписка: снова не пустая (12 конфигов на 24.11), обновляется редко
        IGARECK + "WHITE-SNI-RU-all.txt",
        // Сборник AvenCores: файлы 1–25, отфильтрованные по CIDR/SNI белых списков. Большой (~1,3 МБ), в MAX_BODY_CHARS влезает
        "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/26.txt",
        // Ещё зеркала igareck (телефонная подписка): GitHub raw при белых списках часто закрыт
        "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://bitbucket.org/igareck/vpn-configs-for-russia/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://gitea.com/igareck/vpn-configs-for-russia/raw/branch/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        // Через Яндекс.Переводчик (домен Яндекса в белом списке): схема из README igareck
        "https://translate.yandex.ru/translate?url=https://bitbucket.org/igareck/vpn-configs-for-russia/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt&lang=de-de",
        // Российские хостинги (экспериментально: Mos.Hub, по отзывам, снесён; GitVerse не во всех белых списках). Мёртвые уберёт автоудаление
        "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
        "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
        "https://gitverse.ru/api/repos/flaafix/AetrisVPN/raw/branch/master/AetrisVPN.txt",
        "https://gitverse.ru/api/repos/MishaLan/MishaLan/raw/branch/master/MishaLan.txt",
        "https://gitverse.ru/api/repos/ru-wbl/wl/raw/branch/master/KvRuVPN/KvRuVPN.txt",
        // Адрес собран по образцу README (сам файл не открывался): проверка на телефоне
        "https://raw.githubusercontent.com/kort0881/vpn-checker-backend/main/checked/RU_Best/ru_white_part1.txt",
        // Не под белые списки напрямую: российские SNI / общий сборник — в LOW_TRUST
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/ru-sni/vless_ru.txt",
        "https://cdn.jsdelivr.net/gh/indelingDanil/MyAppVPN-subs@output/vless.txt",
    )

    /** Источники, которые по умолчанию не собираем (из общего списка): сами конфиги оттуда не нужны. */
    private val EXCLUDED_HOSTS = listOf("your-durev.com")

    fun isExcluded(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return EXCLUDED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Исключение касается общего списка и встроенных адресов; ссылки, добавленные самим пользователем, не трогаем. */
    fun isExcluded(url: String, mine: Collection<String>): Boolean = url !in mine && isExcluded(url)

    fun forMode(mode: Mode): List<String> = if (mode == Mode.BLACKLIST) BLACK else WHITE

    /** Ссылка GitHub «blob» → прямая (raw): так её отдаёт сам файл, а не страница. */
    fun raw(url: String): String {
        val m = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url) ?: return url
        val (user, repo, rest) = m.destructured
        return "https://raw.githubusercontent.com/$user/$repo/refs/heads/$rest"
    }

    /**
     * Общие и иранские сборники, добавленные для объёма: доля рабочих у них в российских сетях низкая, поэтому пока опыта нет
     * (после импорта копии, новой установки) их конфиги пробуются после остальных. Это «низкий» приоритет по умолчанию;
     * ручной и авто-приоритет по свежим проверкам (источник себя показал) его заменяют.
     */
    val LOW_TRUST: Set<String> = setOf(
        "ShatakVPN/ConfigForge-V2Ray", "Delta-Kronecker/V2ray-Config", "Mohammadgb0078/IRV2ray", "iboxz/free-v2ray-collector",
        "MatinGhanbari/v2ray-configs", 
        "Pawdroid/Free-servers", "roosterkid/openproxylist",
        "kort0881/vpn-vless-configs-russia", "indelingDanil/MyAppVPN-subs",
    )

    fun isLowTrust(url: String): Boolean = LOW_TRUST.any { url.contains(it) }
}
