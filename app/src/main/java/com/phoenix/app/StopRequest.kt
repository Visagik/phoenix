package com.phoenix.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Кнопка «Остановить и сохранить» на главной. Перепроверка, проверка приложений и подбор для целей сами смотрят сюда и
 * заканчивают работу: уже полученные результаты записаны (они сохраняются по ходу), дальше ничего не запускается.
 * Запрос действует только на то, что началось раньше него: новая работа после нажатия не отменяется.
 */
object StopRequest {
    /** Время последнего нажатия, 0 — не нажимали. Для интерфейса («Останавливаю…»). */
    val at = MutableStateFlow(0L)

    fun request(now: Long = System.currentTimeMillis()) { at.value = now }

    /** Нажимали ли «Остановить» после начала работы [startedAt]. */
    fun requestedSince(startedAt: Long): Boolean = at.value >= startedAt && at.value > 0

    /** Для показа в интерфейсе: остановка запрошена, а работа ещё идёт (идёт с [startedAt] или позже неизвестно — берём окно в 2 минуты). */
    fun pending(now: Long = System.currentTimeMillis()): Boolean = at.value > 0 && now - at.value < 120_000
}
