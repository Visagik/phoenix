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
    )

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
