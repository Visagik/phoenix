package com.phoenix.app

/**
 * Итог принудительной проверки для кнопки «Умная смена»: что не так (сеть, интернет, сервер) и надо ли менять сервер.
 * Если у телефона нет сети или у самой сети нет интернета, менять сервер бессмысленно — только раскручивать ядро.
 */
object SmartVerdict {
    enum class Kind { NO_NETWORK, NO_INTERNET, SERVER_DEAD, TUNNEL_OK }

    data class Result(val kind: Kind, val text: String, val switchServer: Boolean)

    /** [app] — название открытого приложения (из целей) или пусто, если оно неизвестно. */
    fun decide(networkNone: Boolean, directOffline: Boolean, tunnelWorks: Boolean, app: String): Result {
        val forApp = if (app.isBlank()) "" else " под «$app»"
        return when {
            networkNone -> Result(Kind.NO_NETWORK, "у телефона нет сети — сервер менять бессмысленно", false)
            directOffline -> Result(Kind.NO_INTERNET, "у самой сети нет интернета (напрямую тоже ничего не открывается) — сервер менять бессмысленно", false)
            !tunnelWorks -> Result(Kind.SERVER_DEAD, "через текущий сервер связи нет — подбираю другой$forApp", true)
            else -> Result(Kind.TUNNEL_OK, "туннель отвечает — подбираю лучший сервер$forApp по вашему запросу", true)
        }
    }
}
