package com.phoenix.app

/** Правила скачивания подписок: когда пробовать через VPN, какие «браузерные» имена клиента пробовать, что считать неудачей источника. */
object FetchPolicy {
    private val agents = listOf("Phoenix/1.0", "v2rayNG/1.10.16", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")

    /** Имя клиента для попытки номер [attempt] (с нуля): подписки часто отдают нужное только «своим» клиентам. */
    fun userAgent(attempt: Int): String = agents[attempt.coerceIn(0, agents.size - 1)]

    /** Через VPN стоит пробовать при обрыве, блокировке (403/429/451), ошибках сервера и «пустом» ответе; 404/410 — файла нет и там. */
    fun tryVpn(code: Int): Boolean = code == 0 || code == 200 || code == 403 || code == 429 || code == 451 || code in 500..599 || code == 408 || code == 401

    /** Ошибка, по которой нельзя судить, что источник умер (его мог закрыть оператор или защита от ботов): без проверки через VPN в счёт неудач не идёт. */
    fun uncertain(code: Int): Boolean = code == 0 || code == 403 || code == 429 || code == 451 || code == 401 || code == 408

    private val redirects = setOf(301, 302, 303, 307, 308)
    fun isRedirect(code: Int) = code in redirects

    /** Ответ похож на подписку: есть ссылки на конфиги (в открытом виде или в base64). */
    fun looksLikeSubscription(body: String): Boolean {
        val text = if (body.contains("://")) body else (LinkParser.decodeBase64(body) ?: body)
        return text.lineSequence().any { l ->
            val t = l.trim()
            t.startsWith("vless://") || t.startsWith("vmess://") || t.startsWith("trojan://") || t.startsWith("ss://") ||
                t.startsWith("hysteria2://") || t.startsWith("hy2://")
        }
    }

    /** Строка для журнала. */
    fun describe(direct: Int, vpn: Int?, vpnOn: Boolean): String = buildString {
        append("напрямую: ").append(if (direct == 0) "нет ответа" else "код $direct")
        when {
            vpn != null -> append("; через VPN: ").append(if (vpn == 0) "нет ответа" else "код $vpn")
            !vpnOn && tryVpn(direct) -> append("; VPN выключен — через него не пробовал")
        }
    }
}

/** Связь чистой логики сбора с Android (сеть, нагрузка): на устройстве заполняется в PhoenixApp, в тестах остаются значения по умолчанию. */
/** Сколько раз за один сбор можно повторять загрузку через VPN (лишние повторы грузят туннель и сеть). */
object VpnRetryBudget {
    const val MAX_PER_RUN = 15

    /** Повтор через VPN: не больше раза на источник за запрос, не при отсутствии сети и не сверх общего лимита сбора. */
    fun allow(alreadyForSource: Boolean, usedInRun: Int, offline: Boolean): Boolean = !alreadyForSource && !offline && usedInRun < MAX_PER_RUN
}

object NetHooks {
    @Volatile var offline: () -> Boolean = { false }
    @Volatile var mobile: () -> Boolean = { false }
    @Volatile var label: () -> String = { "" }
    @Volatile var level: (Storage, String) -> Int = { _, _ -> 0 }
}
