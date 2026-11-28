package com.phoenix.app

/**
 * Решение «что делать после подтверждённого обрыва связи через сервер» одной чистой функцией (раньше это была цепочка из десятка `if` внутри
 * `checkHealth`, которую нельзя было проверить без телефона). Порядок условий — от дешёвого к дорогому и от «виновата сеть» к «виноват сервер»:
 * дорогой прямой замер запрашивается ([Action.MeasureDirect]) только когда без него уже нельзя, а решение принимается заново с его результатом.
 */
object HealthDecision {
    enum class Action {
        /** Автопереподключение выключено: только показать «нет связи через сервер». */
        BROKEN_NO_RECONNECT,
        /** У телефона нет сети вообще. */
        NO_NETWORK,
        /** Нужен прямой замер (мимо VPN): есть ли у самой сети интернет. */
        MEASURE_DIRECT,
        /** У самой сети нет интернета — серверы не перебираем. */
        NO_INTERNET,
        /** Режим взят по подсказке, связи нет: ждём ответа определения. */
        DEFER_PROVISIONAL,
        /** Определение показывает другой тип блокировок, режим ещё не сменился. */
        DEFER_MODE_PENDING,
        /** Сервер закреплён вручную. */
        DEFER_PINNED,
        /** Режим только что сменился. */
        DEFER_MODE_SETTLING,
        /** Сеть только что менялась или дёргалась. */
        DEFER_NET_SETTLING,
        /** Сеть прыгает между типами. */
        DEFER_NET_FLAPPING,
        /** Сервер виноват (страйк записан), но недавно уже перебирали — ждём. */
        BLAME_THEN_WAIT,
        /** Сервер виноват: записать провал и перебрать серверы. */
        BLAME_AND_FAILOVER,
    }

    /**
     * [directOffline] — результат прямого замера или недавний «нет интернета» (null — ещё не измеряли).
     * [modePending] уже учитывает автоопределение и ручную фиксацию режима.
     */
    data class Snapshot(
        val autoReconnect: Boolean,
        val noNetwork: Boolean,
        val directOffline: Boolean?,
        /** Проверяется только когда до него дошла очередь: у проверки есть побочные эффекты (запуск определения). */
        val provisionalDoubt: () -> Boolean,
        val modePending: Boolean,
        /** Тоже лениво: проверка закрепления может снять истёкшее закрепление и пишет в журнал. */
        val pinnedAndRunning: () -> Boolean,
        val modeSettling: Boolean,
        val netSettling: Boolean,
        val netFlapping: Boolean,
        val recentlyFailedOver: Boolean,
    )

    fun decide(s: Snapshot): Action = when {
        !s.autoReconnect -> Action.BROKEN_NO_RECONNECT
        s.noNetwork -> Action.NO_NETWORK
        s.directOffline == null -> Action.MEASURE_DIRECT
        s.directOffline -> Action.NO_INTERNET
        s.provisionalDoubt() -> Action.DEFER_PROVISIONAL
        s.modePending -> Action.DEFER_MODE_PENDING
        s.pinnedAndRunning() -> Action.DEFER_PINNED
        s.modeSettling -> Action.DEFER_MODE_SETTLING
        s.netSettling -> Action.DEFER_NET_SETTLING
        s.netFlapping -> Action.DEFER_NET_FLAPPING
        s.recentlyFailedOver -> Action.BLAME_THEN_WAIT
        else -> Action.BLAME_AND_FAILOVER
    }
}
