package com.phoenix.app

/**
 * Встроенные источники конфигов по режимам: добавляются к общему списку и к своим ссылкам.
 * Любой можно отключить через blacklist источников (вкладка «Источники»).
 */
object DefaultSources {
    private const val IGARECK = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/"

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
        // Белые списки от igareck (мобильные сети): 150 лучших для телефона и проверенный набор по CIDR. WHITE-SNI-RU-all убран: на проверке файл был пустой
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
    )

    fun isLowTrust(url: String): Boolean = LOW_TRUST.any { url.contains(it) }
}
