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

    fun cells(check: CheckResult, selected: List<CheckService>, vetoed: (CheckService) -> Boolean = { false }, odds: ((String) -> Int?)?): List<AppCell> = selected.map { s ->
        val ms = check.ms(s)
        val mark = when {
            vetoed(s) -> "✗" // вы отметили «Не работает» для этого сайта на этом сервере: старый замер не в счёт
            ms == null -> "?"
            ms >= 0 -> "✓"
            ms == CheckServices.PARTIAL -> "≈"
            else -> "✗"
        }
        AppCell(s.short, s.title, mark, odds?.invoke(s.id))
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
        vetoed: ((link: String, url: String) -> Boolean)? = null,
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
                apps = if (check != null && !check.dead) ServerChoice.cells(check, selected, vetoed = { s -> vetoed?.invoke(c.link, s.url) == true }, odds = odds?.let { f -> { sid -> f(CheckStore.keyOf(c.link), sid) } }) else emptyList(),
                current = c.identity == currentId || c.host.isNotEmpty() && c.host == ConfigIdentity.host(currentLink),
            )
        }
    }
}
