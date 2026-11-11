package com.phoenix.app

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Пользовательские настройки и служебные отметки (SharedPreferences).
 *
 * Часть настроек можно задать отдельно для каждого режима (белые / чёрные списки): см. [SettingsKeys.SCOPED].
 * По умолчанию ([Scope.AUTO]) значения читаются и пишутся для активного режима: если у него включены «свои настройки» —
 * из них, иначе из общих. Экран настроек открывает нужную область явно: общие или конкретный режим.
 */
class AppSettings(context: Context, private val scope: Scope = Scope.AUTO) {
    enum class Scope { AUTO, COMMON, WHITELIST, BLACKLIST }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("phoenix", Context.MODE_PRIVATE)

    /** Имя режима, чьи ключи сейчас в работе; null — общие. */
    private fun modeName(): String? = when (scope) {
        Scope.COMMON -> null
        Scope.WHITELIST -> Mode.WHITELIST.name
        Scope.BLACKLIST -> Mode.BLACKLIST.name
        Scope.AUTO -> Mode.read(appContext.filesDir).name.takeIf { prefs.getBoolean(SettingsKeys.separateKey(it), false) }
    }

    private fun k(base: String): String = SettingsKeys.scopedKey(base, modeName())

    private fun bool(base: String, def: Boolean) = prefs.getBoolean(k(base), def)
    private fun putBool(base: String, v: Boolean) = prefs.edit().putBoolean(k(base), v).apply()

    /** Есть ли у режима свои настройки (иначе действуют общие). */
    fun isSeparate(mode: Mode): Boolean = prefs.getBoolean(SettingsKeys.separateKey(mode.name), false)

    /** Включить или выключить режиму «свои настройки». При включении сначала копируются текущие общие значения. */
    fun setSeparate(mode: Mode, on: Boolean) {
        val e = prefs.edit()
        if (on && !isSeparate(mode)) {
            val all = prefs.all
            for ((from, to) in SettingsKeys.copyPlan(all.keys, mode.name)) {
                when (val v = all[from]) {
                    is Boolean -> e.putBoolean(to, v)
                    is Int -> e.putInt(to, v)
                    is Float -> e.putFloat(to, v)
                    is Long -> e.putLong(to, v)
                    is String -> e.putString(to, v)
                    else -> {}
                }
            }
        }
        e.putBoolean(SettingsKeys.separateKey(mode.name), on).apply()
    }

    /** Названия настроек режима, которые отличаются от общих. */
    fun overriddenLabels(mode: Mode): List<String> {
        if (!isSeparate(mode)) return emptyList()
        return SettingsKeys.overridden(prefs.all, mode.name).map { SettingsKeys.LABELS[it] ?: it }
    }

    /** Вернуть режим к общим настройкам: убрать его значения и выключить «свои настройки». */
    fun resetOverrides(mode: Mode) {
        val e = prefs.edit()
        SettingsKeys.resetKeys(prefs.all.keys, mode.name).forEach { e.remove(it) }
        e.putBoolean(SettingsKeys.separateKey(mode.name), false).apply()
    }

    /** Во сколько запускать ночной сбор. По умолчанию 00:05, как в cron. */
    var runHour: Int
        get() = prefs.getInt("run_hour", 0).coerceIn(0, 23)
        set(v) = prefs.edit().putInt("run_hour", v.coerceIn(0, 23)).apply()

    var runMinute: Int
        get() = prefs.getInt("run_minute", 5).coerceIn(0, 59)
        set(v) = prefs.edit().putInt("run_minute", v.coerceIn(0, 59)).apply()

    /** Запускать сбор только когда телефон на зарядке (экономия батареи). */
    var onlyCharging: Boolean
        get() = prefs.getBoolean("only_charging", false)
        set(v) = prefs.edit().putBoolean("only_charging", v).apply()

    // ---- режим, переподключение, политика целей ----

    /** Сам определять тип блокировок (белые/чёрные списки) и переключать режим. */
    var autoMode: Boolean
        get() = prefs.getBoolean("auto_mode", true)
        set(v) = prefs.edit().putBoolean("auto_mode", v).apply()

    /** Когда и в какой сети режим выбран вручную (автоопределение после этого ненадолго молчит). */
    var manualModeAt: Long
        get() = prefs.getLong("manual_mode_at", 0L)
        set(v) = prefs.edit().putLong("manual_mode_at", v).apply()

    var manualModeNet: String
        get() = prefs.getString("manual_mode_net", "") ?: ""
        set(v) = prefs.edit().putString("manual_mode_net", v).apply()

    /** Результат последнего определения типа блокировок (для показа). */
    var lastDetect: String
        get() = prefs.getString("last_detect", "") ?: ""
        set(v) = prefs.edit().putString("last_detect", v).apply()

    var lastDetectAt: Long
        get() = prefs.getLong("last_detect_at", 0L)
        set(v) = prefs.edit().putLong("last_detect_at", v).apply()

    /** Сам переподключаться на запасной конфиг, если туннель перестал работать. */
    var autoReconnect: Boolean
        get() = bool("auto_reconnect", true)
        set(v) = putBool("auto_reconnect", v)

    /** Общий выбор для целей: А (только она через свой сервер) или Б (переключить весь VPN). */
    var globalPolicy: Policy
        get() = Policy.entries.firstOrNull { it.name == prefs.getString(k("global_policy"), null) && it != Policy.DEFAULT } ?: Policy.AUTO
        set(v) = prefs.edit().putString(k("global_policy"), v.name).apply()

    /** Мерить скорость и по мобильной сети (тратит трафик, по умолчанию только Wi-Fi). */
    var speedOnMetered: Boolean
        get() = bool("speed_on_metered", false)
        set(v) = putBool("speed_on_metered", v)

    /** Менять сервер, если через него почти ничего не качается (интернет «есть», но страницы и фото не грузятся). */
    var slowSwitch: Boolean
        get() = bool("slow_switch", true)
        set(v) = putBool("slow_switch", v)

    /** Ниже этой скорости (Мбит/с) при активной работе считаем сервер медленным. */
    var slowMinMbps: Float
        get() = prefs.getFloat(k("slow_min_mbps"), 0.5f).coerceIn(0.1f, 20f)
        set(v) = prefs.edit().putFloat(k("slow_min_mbps"), v.coerceIn(0.1f, 20f)).apply()

    /** Устаревшее общее поле: теперь последнее подключение хранится по режимам (Storage.lastConnection). Нужно только для переноса. */
    var lastLink: String
        get() = prefs.getString("last_link", "") ?: ""
        set(v) = prefs.edit().putString("last_link", v).apply()

    var lastTitle: String
        get() = prefs.getString("last_title", "") ?: ""
        set(v) = prefs.edit().putString("last_title", v).apply()

    /** Подключать VPN сам при включении телефона и при открытии приложения. По умолчанию выключено. */
    var autoConnect: Boolean
        get() = bool("auto_connect", false)
        set(v) = putBool("auto_connect", v)

    /** Как быть с IPv6: по умолчанию перехватывается и блокируется (приложения переходят на IPv4), утечек мимо VPN нет. */
    var ipv6Mode: Ipv6Mode
        get() = Ipv6Mode.entries.firstOrNull { it.name == prefs.getString(k("ipv6_mode"), null) } ?: Ipv6Mode.BLOCK
        set(v) = prefs.edit().putString(k("ipv6_mode"), v.name).apply()

    /** Если VPN не удалось поднять или он оборвался — держать туннель и не пускать трафик в обход. По умолчанию выключено. */
    var killSwitch: Boolean
        get() = bool("kill_switch", false)
        set(v) = putBool("kill_switch", v)

    /**
     * Закрывать QUIC (UDP 443). Многие серверы не пропускают UDP, и приложения долго ждут, прежде чем перейти на обычный TCP;
     * при закрытом QUIC они переходят сразу. Включено по умолчанию.
     */
    var blockQuic: Boolean
        get() = bool("block_quic", true)
        set(v) = putBool("block_quic", v)

    /** Движок туннеля hev-socks5-tunnel (как у v2rayNG) вместо встроенного в ядро. Общая настройка, по умолчанию выключено. */
    var hevEngine: Boolean
        get() = prefs.getBoolean("tun_engine_hev", false)
        set(v) = prefs.edit().putBoolean("tun_engine_hev", v).apply()

    /** Необязательная ссылка (https) на файл правил регионов: какие сайты закрыты по стране выхода и по каким фразам узнавать страницу-заглушку. */
    var geoRulesUrl: String
        get() = prefs.getString("geo_rules_url", "") ?: ""
        set(v) = prefs.edit().putString("geo_rules_url", v.trim()).apply()

    /** Если запасной сервер не нашёлся, само запускать полную перепроверку всех конфигов (не чаще раза в 30 минут). */
    var autoRecheck: Boolean
        get() = bool("auto_recheck", true)
        set(v) = putBool("auto_recheck", v)

    /** Сами проверять выбранные сервисы у рабочих конфигов (после перепроверки и в фоне). По умолчанию включено. */
    var autoServiceCheck: Boolean
        get() = bool("auto_service_check", true)
        set(v) = putBool("auto_service_check", v)

    // ---- расписание сбора (общее) ----

    var scheduleKind: ScheduleKind
        get() = ScheduleKind.entries.firstOrNull { it.name == prefs.getString("schedule_kind", null) } ?: ScheduleKind.DAILY
        set(v) = prefs.edit().putString("schedule_kind", v.name).apply()

    /** Для режима «раз в несколько часов»: через сколько часов после последнего удачного сбора собирать снова. */
    var intervalHours: Int
        get() = prefs.getInt("interval_hours", 12).coerceIn(1, 168)
        set(v) = prefs.edit().putInt("interval_hours", v.coerceIn(1, 168)).apply()

    /** Когда последний раз сбор удался для всех режимов (мс); нужен режиму «раз в несколько часов». */
    var lastSuccessAt: Long
        get() = prefs.getLong("last_success_at", 0L)
        set(v) = prefs.edit().putLong("last_success_at", v).apply()

    /** Когда последний раз запускалась полная перепроверка конфигов (мс). */
    var lastRecheckAt: Long
        get() = prefs.getLong("last_recheck_at", 0L)
        set(v) = prefs.edit().putLong("last_recheck_at", v).apply()

    /** Автоматические тихие часы по часам активности (когда телефоном обычно не пользуются). */
    var quietAuto: Boolean
        get() = prefs.getBoolean("quiet_auto", true)
        set(v) = prefs.edit().putBoolean("quiet_auto", v).apply()

    /** «Фокус»: пока открыто приложение из целей, тяжёлые проверки и смена сервера по скорости ждут. */
    var focusMode: Boolean
        get() = prefs.getBoolean("focus_mode", false)
        set(v) = prefs.edit().putBoolean("focus_mode", v).apply()

    /** Пользователь сам отметил, что включил автозапуск (система не даёт это проверить). */
    var autostartConfirmed: Boolean
        get() = prefs.getBoolean("autostart_confirmed", false)
        set(v) = prefs.edit().putBoolean("autostart_confirmed", v).apply()

    /** При смене сервера учитывать, какое приложение открыто сейчас: первыми пробуются серверы, на которых оно работает. */
    var appAwareFailover: Boolean
        get() = prefs.getBoolean("app_aware_failover", true)
        set(v) = prefs.edit().putBoolean("app_aware_failover", v).apply()

    /** Свои тихие часы: сильнее автоматических. */
    var quietManual: Boolean
        get() = prefs.getBoolean("quiet_manual", false)
        set(v) = prefs.edit().putBoolean("quiet_manual", v).apply()

    var quietFrom: Int
        get() = prefs.getInt("quiet_from", 23).coerceIn(0, 23)
        set(v) = prefs.edit().putInt("quiet_from", v.coerceIn(0, 23)).apply()

    var quietTo: Int
        get() = prefs.getInt("quiet_to", 7).coerceIn(0, 23)
        set(v) = prefs.edit().putInt("quiet_to", v.coerceIn(0, 23)).apply()

    /** Когда последний раз целиком отработала перепроверка целей (мс): нужно, чтобы тяжёлое без зарядки не голодало. */
    var lastTargetsAt: Long
        get() = prefs.getLong("last_targets_at", 0L)
        set(v) = prefs.edit().putLong("last_targets_at", v).apply()

    /** Настройки, которые уходят в резервную копию (ключ → тип: b, i, f, s). Служебные отметки сбора не переносим. */
    private val portable = SettingsKeys.portable(Mode.entries.map { it.name })

    fun exportMap(): Map<String, String> {
        val all = prefs.all
        return portable.keys.filter { all[it] != null }.associateWith { all[it].toString() }
    }

    /** @return сколько настроек применено */
    fun importMap(map: Map<String, String>): Int {
        val e = prefs.edit()
        var n = 0
        for ((k, t) in portable) {
            val v = map[k] ?: continue
            when (t) {
                'b' -> v.toBooleanStrictOrNull()?.let { e.putBoolean(k, it); n++ }
                'i' -> v.toIntOrNull()?.let { e.putInt(k, it); n++ }
                'f' -> v.toFloatOrNull()?.let { e.putFloat(k, it); n++ }
                else -> {
                    val ok = when {
                        k.endsWith("global_policy") -> Policy.entries.any { it.name == v }
                        k.endsWith("ipv6_mode") -> Ipv6Mode.entries.any { it.name == v }
                        k == "schedule_kind" -> ScheduleKind.entries.any { it.name == v }
                        else -> true
                    }
                    if (ok) { e.putString(k, v); n++ }
                }
            }
        }
        e.apply()
        return n
    }

    fun timeText(): String = "%02d:%02d".format(runHour, runMinute)

    // ---- диагностика запусков ----

    /** Когда началась последняя попытка сбора (мс), 0 — не было. */
    var lastAttemptAt: Long
        get() = prefs.getLong("last_attempt_at", 0L)
        set(v) = prefs.edit().putLong("last_attempt_at", v).apply()

    /** Кто запустил последний сбор: alarm / worker / manual / catch-up / boot. */
    var lastTrigger: String
        get() = prefs.getString("last_trigger", "") ?: ""
        set(v) = prefs.edit().putString("last_trigger", v).apply()

    var lastResult: String
        get() = prefs.getString("last_result", "") ?: ""
        set(v) = prefs.edit().putString("last_result", v).apply()

    /** На какое время (мс) поставлен будильник и точный ли он. */
    var alarmAt: Long
        get() = prefs.getLong("alarm_at", 0L)
        set(v) = prefs.edit().putLong("alarm_at", v).apply()

    var alarmExact: Boolean
        get() = prefs.getBoolean("alarm_exact", false)
        set(v) = prefs.edit().putBoolean("alarm_exact", v).apply()

    fun recordAttempt(trigger: String) {
        prefs.edit().putLong("last_attempt_at", System.currentTimeMillis()).putString("last_trigger", trigger)
            .putString("last_result", "выполняется…").apply()
    }

    fun recordResult(summary: String) {
        prefs.edit().putString("last_result", summary).apply()
    }

    fun lastRunText(): String {
        val at = lastAttemptAt
        if (at == 0L) return "ещё не запускался"
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault()))
        return "$time · ${lastTrigger.ifBlank { "?" }} · ${lastResult.ifBlank { "?" }}"
    }
}
