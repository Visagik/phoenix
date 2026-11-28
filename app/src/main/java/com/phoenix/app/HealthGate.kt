package com.phoenix.app

import java.io.File

/**
 * Когда сбой проверки связи считать виной сервера. Журналы 20–21.11: около 30 % падений восстанавливались сами, а смена сервера
 * шла через секунды после предупреждения системы «сеть пропадёт» (простой 50 с).
 */
object HealthGate {
    /** После смены сети или предупреждения системы сбой связи не вешаем на сервер и не перебираем. */
    const val SETTLE_MS = 60_000L
    /** «Недавно» для определения типа блокировок: раньше второй неудачи оно нужно только сразу после смены сети. */
    const val RECENT_MS = 120_000L
    /** Сервер, упавший раньше этого после выбора, получает долгий карантин. */
    const val EARLY_FALL_MS = 120_000L

    fun settling(now: Long, shakeAt: Long): Boolean = shakeAt > 0L && now - shakeAt in 0 until SETTLE_MS
    fun recent(now: Long, shakeAt: Long): Boolean = shakeAt > 0L && now - shakeAt in 0 until RECENT_MS
    /** Сколько неудач подряд нужно: на мобильной сети провалы короче, ждём третью. */
    fun failuresNeeded(mobile: Boolean): Int = if (mobile) 3 else 2
    /** На какой неудаче запускать определение режима (15 прямых запросов): сразу после смены сети — на первой, иначе на второй. */
    /**
     * На какой неудаче запускать прямой замер заранее, в фоне (С3): за одну неудачу до решающей, чтобы к решению он был готов
     * (раньше после решающей неудачи ждали ещё до 14 с, при подозрении вдвое больше). На мобильной сети это 2-я, на Wi-Fi 1-я.
     */
    fun measureAheadAt(mobile: Boolean): Int = failuresNeeded(mobile) - 1

    /** Сколько живёт замер, сделанный заранее: старше решение по нему не принимаем (интернет у сети мог пропасть). Р2 */
    const val AHEAD_MAX_AGE_MS = 60_000L

    /** Годится ли замер заранее: тот же эпизод, та же сеть и возраст не больше [AHEAD_MAX_AGE_MS]. */
    fun aheadUsable(since: Long, epoch: Long, at: Long, nowSince: Long, nowEpoch: Long, now: Long): Boolean =
        since == nowSince && epoch == nowEpoch && now - at in 0..AHEAD_MAX_AGE_MS
    fun detectAt(netRecent: Boolean): Int = if (netRecent) 1 else 2
    fun earlyFall(now: Long, chosenAt: Long): Boolean = chosenAt > 0L && now - chosenAt in 0 until EARLY_FALL_MS
}


/**
 * С какого момента считать перерыв связи. Раньше счёт шёл от начала смены сервера/режима, а связь пропадала раньше:
 * 17 с до смены сервера (08:20:57 → 08:21:14) и около 47 с при смене режима (сбой 07:06:27, смена 07:07:14; счётчик показал 43 с вместо ~92).
 * Берём время первой неудачи проверки связи, если она была недавно и не относится к давнему эпизоду.
 */
/** Ответ одного адреса проверки связи: [ok] — адрес открылся, иначе [detail] — «хост: причина». */
data class HealthAnswer(val index: Int, val ok: Boolean, val detail: String)

/** Итог проверки связи по ответам всех адресов, пришедшим в любом порядке (С3: адреса опрашиваются одновременно). */
object HealthSummary {
    /** Хоть один открылся — связь есть. */
    fun ok(answers: List<HealthAnswer>): Boolean = answers.any { it.ok }

    /** Причины неудачных ответов в порядке адресов списка, как при последовательной проверке; у успеха причины нет. */
    fun detail(answers: List<HealthAnswer>): String =
        if (ok(answers)) "" else answers.sortedBy { it.index }.filter { it.detail.isNotEmpty() }.joinToString("; ") { it.detail }
}

object OutageStart {
    const val WINDOW_MS = 3 * 60_000L
    /**
     * Начало перерыва: время первой неудачи текущего эпизода, если оно свежее; иначе «сейчас». [lastConnectedAt] — когда связь была в порядке
     * в последний раз: перерыв не может начаться раньше (начало из прошлого эпизода, оставшееся без обнуления, давало до трёх строк DOWNTIME с одним началом, С1).
     */
    fun pick(now: Long, failSince: Long, lastConnectedAt: Long = 0L): Long {
        if (failSince <= 0L) return now
        val start = maxOf(failSince, lastConnectedAt)
        return if (now - start in 0..WINDOW_MS) start else now
    }
}

/**
 * Общая отметка «сеть сейчас часто меняется»: пока она стоит, тяжёлые фоновые задачи (полная перепроверка, проверка целей) откладываются,
 * чтобы не гонять проверки по сети, которая через минуту станет другой (журнал 06.11: 24 смены режима за 11 часов).
 */
object NetFlap {
    const val DEFER_MS = NetState.FLAP_HOLD_MS
    // окна и состояние живут в NetState; здесь только прежнее имя для перепроверки и фоновой задачи
    fun mark(now: Long) = NetState.flap(now)
    fun busy(now: Long): Boolean = NetState.isFlapping(now)
    fun clear() = NetState.clear()
}

/**
 * Частые смены сети (Wi-Fi ↔ мобильная, журнал 06.11: 32 смены и 24 смены режима за 11 часов).
 * Если сеть меняется ≥ [LIMIT] раз за [WINDOW_MS], одного определения типа блокировок мало: режим подтверждается дольше,
 * иначе каждая смена запускает дорогое переключение режима и перебор серверов.
 */
class FlapGuard(private val windowMs: Long = WINDOW_MS, private val limit: Int = LIMIT) {
    private val times = ArrayList<Long>()

    @Synchronized fun note(now: Long) {
        trim(now)
        times.add(now)
    }

    @Synchronized fun count(now: Long): Int {
        trim(now)
        return times.size
    }

    fun flapping(now: Long): Boolean = count(now) >= limit

    private fun trim(now: Long) {
        times.removeAll { now - it > windowMs || now < it }
    }

    companion object {
        const val WINDOW_MS = 180_000L
        const val LIMIT = 2
    }
}

/**
 * Контрольный адрес для проверки списка обхода. В проход по «пора проверить» попадают в основном закрытые напрямую сервисы
 * (доверенные пропускаются), поэтому «не открылось ничего» было обычным итогом и принималось за отсутствие сети: на Xiaomi 44 раза за сутки,
 * с повторами через 8 и 20 с на каждую смену сети. Теперь отсутствие сети подтверждает не открывшийся и доверенный сервис.
 */
object DirectControl {
    /** До [n] доверенных сервисов, которых нет в проверяемых сейчас. */
    fun pick(allIds: List<String>, dueIds: Set<String>, trustedIds: Set<String>, n: Int = 2): List<String> =
        allIds.filter { it in trustedIds && it !in dueIds }.take(n)

    /** true — сети нет: не открылось ничего и контрольных адресов нет или они тоже не открылись. */
    fun noNetwork(anyOpened: Boolean, controlsTried: Boolean, controlOpened: Boolean): Boolean =
        !anyOpened && !(controlsTried && controlOpened)
}
