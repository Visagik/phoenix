package com.phoenix.app

/**
 * Первый запуск (план Opus, раздел G): короткие экраны с одним вопросом на каждом. Пройденная версия хранится числом, а не флагом:
 * когда появятся новые шаги (since больше сохранённого числа), покажутся только они. Экраны с уже выданным правом пропускаются.
 */
object Onboarding {
    /** Номер последней версии набора шагов; при добавлении шага увеличить и задать ему since = новое число. */
    const val VERSION = 2

    enum class Step(val since: Int) {
        WELCOME(1),
        VPN(1),
        NOTIFICATIONS(1),
        BACKGROUND(1),
        ALARMS(1),
        AUTOSTART(1),
        /** «Доступ к данным об использовании»: без него не работает переключение сервера при открытии приложения. */
        USAGE(2),
        SERVERS(1),
        /** Финальная карточка: «свой сервер для приложений» и раздел «Фишки». Для тех, кто прошёл версию 1, это единственный новый шаг. */
        TIPS(2),
    }

    data class Env(
        val vpnGranted: Boolean,
        val notificationsRelevant: Boolean,
        val notificationsGranted: Boolean,
        val batteryOk: Boolean,
        val exactRelevant: Boolean,
        val exactOk: Boolean,
        val autostartRelevant: Boolean,
        val autostartConfirmed: Boolean,
        val hasServers: Boolean,
        val usageGranted: Boolean = true,
    )

    /** Шаг уже не нужен: право выдано, шаг не относится к этому телефону или серверы уже есть. «Приветствие» не выполняется никогда. */
    fun satisfied(step: Step, env: Env): Boolean = when (step) {
        Step.WELCOME -> false
        Step.VPN -> env.vpnGranted
        Step.NOTIFICATIONS -> !env.notificationsRelevant || env.notificationsGranted
        Step.BACKGROUND -> env.batteryOk
        Step.ALARMS -> !env.exactRelevant || env.exactOk
        Step.AUTOSTART -> !env.autostartRelevant || env.autostartConfirmed
        Step.USAGE -> env.usageGranted
        Step.SERVERS -> env.hasServers
        Step.TIPS -> false
    }

    /**
     * Какие экраны показать. [seen] — сохранённый номер версии. Тот, кто уже пользуется приложением (серверы есть), при первом
     * обновлении до онбординга ничего не видит — вернуться можно в «Ещё → Помощь и данные → Первые шаги» ([force]).
     * Если кроме приветствия показывать нечего, онбординг не нужен.
     */
    fun steps(seen: Int, env: Env, force: Boolean = false): List<Step> {
        if (!force && (seen >= VERSION || (seen == 0 && env.hasServers))) return emptyList()
        val list = Step.entries.filter { (force || it.since > seen) && !satisfied(it, env) }
        return if (!force && list.none { it != Step.WELCOME }) emptyList() else list
    }
}
