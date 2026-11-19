package com.phoenix.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Когда анимации главного экрана допустимы: включены в приложении и в системе не выставлено «Убрать анимацию» (шкала аниматоров 0). */
object AnimPolicy {
    fun enabled(userOn: Boolean, systemScale: Float): Boolean = userOn && systemScale > 0f

    /** Период «дыхания» свечения круга, мс; доля прозрачности и размера от фазы 0..1. */
    const val GLOW_PERIOD_MS = 2500
    const val GLOW_MAX_SCALE = 0.08f
    const val GLOW_MAX_ALPHA = 0.35f
    fun glowScale(phase: Float): Float = 1f + GLOW_MAX_SCALE * phase.coerceIn(0f, 1f)
    fun glowAlpha(phase: Float): Float = GLOW_MAX_ALPHA * (1f - phase.coerceIn(0f, 1f))
}

/** Выбор пользователя («Ещё → Внешний вид → Анимации»); читается главным экраном. */
object UiAnim {
    val userOn = MutableStateFlow(true)
}
