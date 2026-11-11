package com.phoenix.app

/** Одна колонка мини-таблицы приложений: [code] — «TG», [mark] — ✓ / ≈ / ✗ / ?, [percent] — шанс работы или null. */
data class AppCell(val code: String, val title: String, val mark: String, val percent: Int?)

/** Строка списка «Сменить сервер»: что за сервер, где он, что открывает и какой отклик. */
data class ServerOption(
    val link: String,
    val title: String,
    /** Страна выхода (код) или пусто. */
    val cc: String,
    /** Свежая проверка подтвердила, что сервер отвечает. */
    val checked: Boolean,
    /** «Claude ✓ · Qwen ≈ · YouTube ✗» либо пусто, если не проверялся. */
    val services: String,
    /** «340 мс · 12.4 Мбит/с» либо пусто. */
    val speed: String,
    val current: Boolean,
    /** Мини-таблица: по колонке на приложение (код, итог проверки, шанс работы). */
    val apps: List<AppCell> = emptyList(),
    /** Страна выхода подтверждена настоящей проверкой, а не взята из названия. */
    val exitChecked: Boolean = false,
    /** «Почему этот сервер»: причины за и против (ServerWhy), по одной на строку. */
    val why: String = "",
) {
    val place: String get() = ServerChoice.country(cc)
}

/** Список рабочих серверов для ручного выбора. Чистая логика: данные ей передают снаружи. */
object ServerChoice {
    private val NAMES = mapOf(
        "RU" to "Россия", "DE" to "Германия", "NL" to "Нидерланды", "US" to "США", "FI" to "Финляндия", "FR" to "Франция",
        "GB" to "Великобритания", "SE" to "Швеция", "PL" to "Польша", "TR" to "Турция", "KZ" to "Казахстан", "BG" to "Болгария",
        "AT" to "Австрия", "CH" to "Швейцария", "LV" to "Латвия", "LT" to "Литва", "EE" to "Эстония", "UA" to "Украина",
        "BY" to "Беларусь", "JP" to "Япония", "SG" to "Сингапур", "HK" to "Гонконг", "CA" to "Канада", "IT" to "Италия",
        "ES" to "Испания", "CZ" to "Чехия", "RO" to "Румыния", "MD" to "Молдова", "AM" to "Армения", "GE" to "Грузия",
        "IN" to "Индия", "AE" to "ОАЭ", "IL" to "Израиль", "NO" to "Норвегия", "DK" to "Дания", "HU" to "Венгрия",
    )

    fun cells(check: CheckResult, selected: List<CheckService>, odds: ((String) -> Int?)?): List<AppCell> = selected.map { s ->
        val ms = check.services[s.id]
        val mark = when {
            ms == null -> "?"
            ms >= 0 -> "✓"
            ms == CheckServices.PARTIAL -> "≈"
            else -> "✗"
        }
        AppCell(s.id, s.title, mark, odds?.invoke(s.id))
    }

    fun country(cc: String): String = if (cc.isBlank()) "страна неизвестна" else NAMES[cc.uppercase()] ?: cc.uppercase()

    /**
     * @param ranked кандидаты в порядке рейтинга; берётся по одному на сервер (хост), плохие и неподдерживаемые пропускаются.
     * Проверенные («отвечает») идут выше непроверенных, внутри — порядок рейтинга. Текущий сервер остаётся в списке (с пометкой).
     */
    fun options(
        ranked: List<Candidate>,
        rows: List<ConfigRow>,
        checks: Map<String, CheckResult>,
        selected: List<CheckService>,
        currentLink: String,
        limit: Int = 40,
        odds: ((key: String, serviceId: String) -> Int?)? = null,
        exitCc: ((key: String) -> String?)? = null,
    ): List<ServerOption> {
        val names = rows.associate { it.raw to it.displayName }
        val currentId = ConfigIdentity.key(currentLink)
        val usable = ranked.filter { !it.bad && XrayConfig.supports(it.link) }
        val byHost = Ranking.distinctServers(usable)
        val ordered = byHost.filter { it.alive } + byHost.filter { !it.alive }
        return ordered.take(limit).map { c ->
            val check = checks[CheckStore.keyOf(c.link)]
            val real = exitCc?.invoke(CheckStore.keyOf(c.link))
            ServerOption(
                link = c.link,
                title = names[c.link] ?: "Phoenix",
                cc = real ?: c.cc,
                exitChecked = real != null,
                why = ServerWhy.explain(c, selected, if (check != null && !check.dead) check else null, real != null).text,
                checked = c.alive,
                services = if (check != null && !check.dead) check.labelNamed(selected, odds?.let { f -> { sid -> f(CheckStore.keyOf(c.link), sid) } }) else "",
                speed = if (check != null && !check.dead) check.speedText() else "",
                apps = if (check != null && !check.dead) ServerChoice.cells(check, selected, odds?.let { f -> { sid -> f(CheckStore.keyOf(c.link), sid) } }) else emptyList(),
                current = c.identity == currentId || c.host.isNotEmpty() && c.host == ConfigIdentity.host(currentLink),
            )
        }
    }
}

/** Когда автоматический перебор серверов (failover) запускать не нужно: он только раскручивает ядро и ничего не находит. */
object FailoverGate {
    /** «Нет сети» по прямой проверке считается свежим столько. */
    const val OFFLINE_WINDOW_MS = 120_000L

    /** После смены режима даём новому серверу устояться, прежде чем объявлять его неработающим. */
    const val MODE_SETTLE_MS = 25_000L

    fun offlineRecently(offlineAt: Long, now: Long): Boolean = offlineAt > 0 && now - offlineAt in 0..OFFLINE_WINDOW_MS

    fun modeSettling(switchedAt: Long, now: Long): Boolean = switchedAt > 0 && now - switchedAt in 0..MODE_SETTLE_MS

    /** Режим сменился, а служба ещё не переехала на его сервер: перебор шёл бы по конфигам не того режима. */
    fun modeOutOfSync(storageMode: Mode, connectedMode: Mode): Boolean = storageMode != connectedMode

    /** Свежее определение уже показывает другой режим, но смена ещё не подтвердилась (нужно два подряд): серверы текущего режима искать рано. */
    const val PENDING_WINDOW_MS = 120_000L

    fun modePending(current: Mode, detected: BlockType?, detectedAt: Long, now: Long): Boolean {
        val target = detected?.let { ModeDecider.modeOf(it) } ?: return false
        return target != current && detectedAt > 0 && now - detectedAt in 0..PENDING_WINDOW_MS
    }
}

/**
 * Охрана долгой работы (перепроверка, поиск целей): если за время работы сменились сеть или режим, результаты относятся к другому
 * миру (в логе: 26-минутная перепроверка через смену Wi-Fi → мобильная; проверки шли, пока ядро перезапускалось при смене режима).
 */
class RunGuard(private val mode: Mode, private val net: String) {
    fun stale(nowMode: Mode, nowNet: String): Boolean = nowMode != mode || nowNet != net

    companion object {
        /** Проверено столько и ни один не ответил: поиск впустую (в логе — 129 ошибок из 159 в белых списках на мобильной сети). */
        const val HOPELESS_AFTER = 200

        fun hopeless(done: Int, alive: Int): Boolean = done >= HOPELESS_AFTER && alive == 0

        /** Причина остановки поиска для журнала. Журнал 06.11 08:27: сеть мигнула и вернулась, а запись говорила «ни один не ответил» при 278 живых. */
        fun abortReason(stale: Boolean, done: Int, alive: Int): String = when {
            stale -> "сменились сеть или режим"
            hopeless(done, alive) -> "за $done проверок ни один сервер не ответил"
            else -> "сеть или режим менялись во время проверки (и вернулись), живых было $alive из $done"
        }
    }
}

/** Когда итогам массовой перепроверки и сбора нельзя верить: сети не было, а не серверы умерли. */
object RecheckGuard {
    /** Из большой проверки не ответил ни один — это сеть, а не 600 мёртвых серверов. */
    fun suspicious(alive: Int, total: Int): Boolean = total >= 50 && alive == 0

    /** Сеть во время проверки менялась: результаты частично записаны в «чужую» сеть. */
    fun networkChanged(before: String, after: String): Boolean = before != after
}
