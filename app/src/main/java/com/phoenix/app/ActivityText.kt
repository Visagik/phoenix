package com.phoenix.app

/** Подписи «чем занят сейчас» с долей и оценкой оставшегося времени. Чистая логика: проверяется тестами. */
/** Как показывать на главном экране, чем занят Phoenix: выкл, одной строкой, подробно (строки и полоса), подробно + хвост журнала. */
object ActivityStyle {
    const val OFF = 0
    const val SHORT = 1
    const val DETAILED = 2
    const val LOG = 3
    const val DEFAULT = DETAILED
    fun clamp(v: Int): Int = if (v in OFF..LOG) v else DEFAULT
}

/** Снимок хода перепроверки для подробной строки на главной. Чистые данные и расчёты. */
data class RecheckDetail(
    val total: Int,
    val done: Int,
    val alive: Int,
    val tcpFailed: Int,
    val probeFailed: Int,
    val skipped: Int,
    val quarantined: Int,
    val elapsedMs: Long,
    val speed: Int,
    val tcpParallel: Int,
    val probeParallel: Int,
    val level: Int,
    val foreign: Boolean,
) {
    /** Проверено серверов в минуту; -1, пока данных мало. */
    fun perMinute(): Int = if (elapsedMs < 8_000 || done < 5) -1 else (done * 60_000L / elapsedMs).toInt()

    /** Не открыли порт, но не «мёртвые» по пробе: порт открыт, а интернета через сервер нет. */
    fun portOpenNoInternet(): Int = probeFailed

    fun eta(): Int = ActivityText.etaSeconds(done, total, elapsedMs)
}

/** Прогресс подбора серверов для целей. */
data class TargetsDetail(val index: Int, val total: Int, val found: Int, val none: Int, val direct: Int, val tested: Int, val paused: Int)

object ActivityText {
    /** «3:07» / «1:02:09»: сколько уже идёт. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Осталось: пусто, пока данных мало (меньше 25 готовых или меньше 8 с), иначе «~N с» / «~N мин». */
    fun etaSeconds(done: Int, total: Int, elapsedMs: Long): Int {
        if (done < 25 || total <= done || elapsedMs < 8_000) return -1
        val perItemMs = elapsedMs.toDouble() / done
        return ((total - done) * perItemMs / 1000.0).toInt().coerceAtLeast(1)
    }

    fun percent(done: Int, total: Int): Int = if (total <= 0) 0 else (done * 100L / total).toInt().coerceIn(0, 100)
}
