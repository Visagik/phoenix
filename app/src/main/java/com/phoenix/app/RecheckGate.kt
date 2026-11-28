package com.phoenix.app

/** Что делать с перепроверкой после сбора (Н4, Р4). Ручной «Собрать» сильнее настройки «Сам перепроверять все конфиги». */
object RecheckGate {
    enum class After {
        /** Ручной сбор, перепроверка не идёт: запускаем всегда, мимо настройки и паузы между перепроверками. */
        MANUAL_RECHECK,

        /** Автоматический сбор при включённой настройке: перепроверка по обычным условиям (пауза, зарядка, сеть) — решает `Recheck.enqueueIfDue`. */
        AUTO_IF_DUE,

        /** Перепроверка уже идёт, либо она выключена настройкой и сбор не ручной: ищем только серверы для целей. */
        TARGETS_ONLY,
    }

    fun afterCollect(manual: Boolean, running: Boolean, autoRecheck: Boolean): After = when {
        running -> After.TARGETS_ONLY
        manual -> After.MANUAL_RECHECK
        autoRecheck -> After.AUTO_IF_DUE
        else -> After.TARGETS_ONLY
    }
}
