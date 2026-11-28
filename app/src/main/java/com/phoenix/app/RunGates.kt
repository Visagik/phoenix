package com.phoenix.app

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

    /**
     * Кнопка «Не работает» / принудительная смена при свежем замере другого режима: менять надо режим, а не искать сервер
     * в режиме, который уже не тот (28.11: 52 с поиска среди чёрных, хотя замер 15 с назад показал белые). null — искать сервер как обычно
     * (автоопределение выключено, режим выбран вручную, замер старый, совпадает с режимом, «не ясно»/«нет сети»).
     */
    fun modeInsteadOfServer(autoMode: Boolean, held: Boolean, current: Mode, lastType: BlockType?, lastTypeAt: Long, now: Long): Mode? {
        if (!autoMode || held) return null
        if (!modePending(current, lastType, lastTypeAt, now)) return null
        return lastType?.let { ModeDecider.modeOf(it) }
    }
}

/**
 * Охрана долгой работы (перепроверка, поиск целей): если за время работы сменились сеть или режим, результаты относятся к другому
 * миру (в логе: 26-минутная перепроверка через смену Wi-Fi → мобильная; проверки шли, пока ядро перезапускалось при смене режима).
 */
class RunGuard(private val mode: Mode, private val net: String) {
    fun stale(nowMode: Mode, nowNet: String): Boolean = nowMode != mode || nowNet != net

    companion object {
        /**
         * Запусков ядра столько и ни один не ответил: поиск впустую. Считаются только пробы через ядро (итоги ≥ 0 и −1), а не отказы TCP:
         * в белых списках TCP не открывается у большинства хостов, и это норма (журнал 24.11 16:00: 217 отказов TCP и 7 проб — остановка
         * через 26 с, а ручная перепроверка в той же сети нашла 14 живых).
         */
        const val HOPELESS_PROBES = 60

        fun hopeless(probed: Int, alive: Int): Boolean = probed >= HOPELESS_PROBES && alive == 0

        /** Причина остановки поиска для журнала. Журнал 06.11 08:27: сеть мигнула и вернулась, а запись говорила «ни один не ответил» при 278 живых. */
        fun abortReason(stale: Boolean, done: Int, alive: Int): String = when {
            stale -> "сменились сеть или режим"
            hopeless(done, alive) -> "за $done запусков ядра ни один не ответил"
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
