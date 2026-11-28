package com.phoenix.app

/**
 * Какие настройки общие, а какие можно задать отдельно для каждого режима (белые/чёрные списки).
 * Отдельная настройка режима хранится под ключом «m_РЕЖИМ_ключ»; флаг «sep_РЕЖИМ» говорит, что у режима свои настройки.
 * Типы значений: b — да/нет, i — целое, f — дробное, s — строка.
 */
object SettingsKeys {
    /** Можно задавать для каждого режима отдельно. */
    val SCOPED: Map<String, Char> = linkedMapOf(
        "auto_reconnect" to 'b',
        "global_policy" to 's',
        "speed_on_metered" to 'b',
        "auto_connect" to 'b',
        "ipv6_mode" to 's',
        "kill_switch" to 'b',
        "slow_switch" to 'b',
        "slow_min_mbps" to 'f',
        "block_quic" to 'b',
        "auto_recheck" to 'b',
        "auto_service_check" to 'b',
    )

    /** Общие для всего приложения: расписание сбора и автоопределение режима. */
    val COMMON: Map<String, Char> = linkedMapOf(
        "run_hour" to 'i',
        "run_minute" to 'i',
        "only_charging" to 'b',
        "auto_mode" to 'b',
        "schedule_kind" to 's',
        "interval_hours" to 'i',
        "app_aware_failover" to 'b',
        "focus_mode" to 'b',
        "autostart_confirmed" to 'b',
        "quiet_auto" to 'b',
        "quiet_manual" to 'b',
        "quiet_from" to 'i',
        "quiet_to" to 'i',
        // добавленные позже; списки «по одному в строке» (страны, приложения) идут отдельными секциями, см. [LISTS]; формат копии построчный
        "animations" to 'b',
        "language" to 's',
        "theme" to 'i',
        "activity_style" to 'i',
        "recheck_speed" to 'i',
        "apps_mode" to 'i',
        "pin_hours" to 'i',
        "bg_wifi_only" to 'b',
        "bg_mobile_mb" to 'i',
        "auto_backup" to 'b',
        "dns_provider" to 's',
        // Н7: раньше не входили в копию, хотя причин нет
        "carry_days" to 'i',
        "tun_engine_hev" to 'b',
        "log_size" to 'i',
        "facts_ticker" to 'b',
        "geo_rules_url" to 's',
    )

    /** Н7: списки «по одному в строке» (страны, приложения): в копии это отдельные секции «lists:<ключ>», а не «ключ=значение». */
    val LISTS: List<String> = listOf("pool_prefer", "pool_avoid", "pool_hidden", "only_apps")

    const val LISTS_PREFIX = "lists:"

    /**
     * Что пишет AppSettings, но в резервную копию не идёт, и почему. Сторож в тесте: новая настройка без решения «в копии или здесь»
     * роняет тест.
     */
    val NOT_IN_BACKUP: Map<String, String> = mapOf(
        "alarm_at" to "служебная отметка будильника", "alarm_exact" to "служебная отметка: точные будильники зависят от телефона",
        "auto_backup_at" to "служебная отметка времени автокопии", "dns_trial_at" to "служебная отметка пробы DNS",
        "dns_trial_prev" to "служебная отметка пробы DNS", "last_attempt_at" to "журнал запусков", "last_detect" to "результат последнего определения",
        "last_detect_at" to "результат последнего определения", "last_link" to "последний выбранный сервер: ключи доступа в копию не кладём",
        "last_recheck_at" to "служебная отметка", "last_result" to "журнал запусков", "last_success_at" to "служебная отметка сбора",
        "last_targets_at" to "служебная отметка", "last_title" to "последний выбранный сервер", "last_trigger" to "журнал запусков",
        "manual_mode_at" to "ручной выбор режима привязан к сети и времени", "manual_mode_net" to "ручной выбор режима привязан к сети и времени",
        "onboarding_seen" to "служебная отметка показа", "whats_new_seen" to "служебная отметка показа",
        "update_apk_sha" to "служебное: обновление", "update_apk_size" to "служебное: обновление", "update_apk_url" to "служебное: обновление",
        "update_checked_at" to "служебное: обновление", "update_notified" to "служебное: обновление", "update_reinstall" to "служебное: обновление",
        "vpn_restart_times" to "служебный счётчик перезапусков VPN", "kill_ts" to "служебная отметка: плашка «система убила процесс»",
        "kill_ack" to "служебная отметка: плашка «система убила процесс»", "kill_code" to "служебная отметка: плашка «система убила процесс»",
        "xray_key_b64" to "ключ установки: в копию не кладём ни при каких условиях", "exit_seen_ts" to "служебная отметка журнала завершений процесса",
        "update_tag" to "служебное: обновление", "update_url" to "служебное: обновление", "vpn_wanted" to "служебная отметка: был ли VPN включён",
    )

    /** Принимать ли значение настройки из копии (копия — внешний файл). Ссылка на правила регионов — только https:// или пустая. */
    fun importValueOk(key: String, value: String): Boolean = if (key == "geo_rules_url") value.isEmpty() || value.startsWith("https://") else true

    /** Человеческие названия режимных настроек (для списка «что переопределено»). */
    val LABELS: Map<String, String> = mapOf(
        "auto_reconnect" to "Переподключаться само",
        "global_policy" to "Политика целей",
        "speed_on_metered" to "Замеры на мобильной сети",
        "auto_connect" to "Автоподключение",
        "ipv6_mode" to "IPv6",
        "kill_switch" to "Защита от обрыва",
        "slow_switch" to "Смена при низкой скорости",
        "slow_min_mbps" to "Порог скорости",
        "block_quic" to "Закрыть QUIC",
        "auto_recheck" to "Автоперепроверка",
        "auto_service_check" to "Автопроверка сервисов",
        // общие настройки экрана «Подключение» (для списка в диалоге сброса)
        "pin_hours" to "Закрепление сервера",
        "focus_mode" to "Режим «Фокус»",
        "app_aware_failover" to "Смена сервера с учётом открытого приложения",
        "dns_provider" to "DNS",
    )

    /**
     * Какие настройки режима реально отличаются от общих. [all] — все значения (общие под своим ключом, режимные под «m_РЕЖИМ_ключ»).
     * Режимное значение без общего (общее ещё не задавали и там действует умолчание) считается переопределённым.
     */
    fun overridden(all: Map<String, Any?>, mode: String): List<String> =
        SCOPED.keys.filter { base ->
            val own = scopedKey(base, mode)
            own in all && all[own] != all[base]
        }

    /** Ключи, которые надо удалить, чтобы режим снова жил по общим настройкам (значения режима; флаг «свои настройки» снимается отдельно). */
    fun resetKeys(present: Set<String>, mode: String): List<String> = SCOPED.keys.map { scopedKey(it, mode) }.filter { it in present }

    fun scopedKey(base: String, mode: String?): String = if (mode == null) base else "m_${mode}_$base"

    fun separateKey(mode: String): String = "sep_$mode"

    /** Всё, что уходит в резервную копию: общие, обычные значения режимов, их отдельные и флаги «свои настройки». */
    /** Режимные настройки, которые есть на экране «Подключение» (не все SCOPED: «Автопроверка сервисов» живёт на экране VPN, «Политика целей» — на экране «Цели»). Н6 */
    val CONNECTION_SCREEN_SCOPED: List<String> = listOf(
        "auto_reconnect", "slow_switch", "slow_min_mbps", "speed_on_metered", "block_quic", "auto_recheck", "auto_connect", "ipv6_mode", "kill_switch",
    )

    /** Общие настройки, которые есть на экране «Подключение»; сбрасываются только в области «Общие». Язык, тема, расписание, тихие часы, режим приложений и автоопределение режима живут на других экранах и не трогаются. */
    val CONNECTION_SCREEN_COMMON: List<String> = listOf("pin_hours", "focus_mode", "app_aware_failover", "dns_provider")

    /** Какие настройки вернёт «Вернуть рекомендуемые» для области [scopeMode] (null — «Общие») — базовые ключи, для диалога. */
    fun resetBaseKeys(scopeMode: String?): List<String> = CONNECTION_SCREEN_SCOPED + (if (scopeMode == null) CONNECTION_SCREEN_COMMON else emptyList())

    /** Ключи, которые удаляются при сбросе: режимные настройки экрана для выбранной области и, только в области «Общие», общие настройки экрана. */
    fun recommendedResetKeys(scopeMode: String?): List<String> =
        CONNECTION_SCREEN_SCOPED.map { scopedKey(it, scopeMode) } + (if (scopeMode == null) CONNECTION_SCREEN_COMMON else emptyList())

    fun portable(modes: List<String>): Map<String, Char> {
        val out = LinkedHashMap<String, Char>()
        out.putAll(COMMON)
        out.putAll(SCOPED)
        for (m in modes) {
            out[separateKey(m)] = 'b'
            for ((k, t) in SCOPED) out[scopedKey(k, m)] = t
        }
        return out
    }

    /** Значения в copy: что переписать из общих в режим при включении «своих настроек» (только то, что реально задано). */
    fun copyPlan(present: Set<String>, mode: String): Map<String, String> =
        SCOPED.keys.filter { it in present }.associateWith { scopedKey(it, mode) }
}

/** Как собирать конфиги: в определённое время каждый день или раз в несколько часов. */
enum class ScheduleKind(val title: String) {
    DAILY("каждый день в точное время"),
    INTERVAL("раз в несколько часов"),
}
