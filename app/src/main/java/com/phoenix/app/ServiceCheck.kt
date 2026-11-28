package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Сервис, доступность которого проверяется через конфиг. */
data class CheckService(
    val id: String,
    val title: String,
    val url: String,
    /** Короткая подпись для колонки (YT, YS…); у сервисов не из целей совпадает с кодом. */
    val short: String = id,
    /** Код встроенного сервиса с тем же адресом: старые результаты проверок лежат под ним. */
    val legacyId: String = "",
)

/** Итог проверки сервиса через сервер: открыт, частично, закрыт, ещё не проверялся. */
enum class ServiceStatus { OPEN, PARTIAL, CLOSED, UNKNOWN }

/** Строка таблицы «цели × сервисы»: название цели, сервер и состояния сервисов в порядке столбцов. */
data class MatrixRow(val label: String, val server: String, val statuses: List<ServiceStatus>)

object CheckServices {
    /**
     * Ядро (measureOutboundDelay) считает успехом только ответ 200 или 204 без ошибок, поэтому для сервисов
     * с защитой от ботов (Claude, Qwen) результат «✗» может быть ложным. Адреса можно поменять здесь.
     */
    val ALL = listOf(
        CheckService("TG", "Telegram", "https://web.telegram.org/"),
        CheckService("QW", "Qwen", "https://chat.qwen.ai/"),
        CheckService("CL", "Claude", "https://claude.ai/"),
        CheckService("YT", "YouTube", "https://www.youtube.com/generate_204"),
        CheckService("ST", "Steam", "https://store.steampowered.com/"),
    )

    /** Встроенные и свои сервисы (свои хранятся отдельно для каждого режима). */
    fun all(custom: List<CheckService>): List<CheckService> = ALL + custom

    /** Контроль: если даже он не открывается, конфиг мёртв и сервисы можно не проверять. */
    const val BASELINE_URL = Baseline.PRIMARY

    const val UNSUPPORTED = -2L
    const val FAILED = -1L

    /** Сайт отвечает, но не 200/204 (защита от ботов, редирект): сервер до него дотянулся, но полноценно ли открывается — неизвестно. */
    const val PARTIAL = -3L

    /** Сервис считается открывшимся: полностью (мс) или частично (отвечает). */
    fun opened(ms: Long?): Boolean = ms != null && (ms >= 0 || ms == PARTIAL)
}

/**
 * Итог проверки одного конфига. Значения сервисов: мс, либо -1 (не открылось).
 * [baselineMs] — реальная задержка HTTPS-запроса через конфиг (не ping). [mbps] — скорость скачивания, -1 если не мерили.
 * [host] — адрес сервера на момент проверки: если конфиг тот же по имени, а адрес сменился, результат устарел.
 */
data class CheckResult(
    val date: String,
    val baselineMs: Long,
    val services: Map<String, Long>,
    val mbps: Double = -1.0,
    val host: String = "",
    /**
     * Сервисы, у которых FAILED поставлен только пометкой пользователя «Не работает» (замер открылся). Живёт в памяти между проверкой и [ServiceStores] и в файл
     * не пишется: пометка не замер, и в историю шансов она не идёт (О3), хотя сам результат остаётся FAILED (значок ✗, выбор сервера под цель).
     */
    val vetoed: Set<String> = emptySet(),
) {
    val dead: Boolean get() = baselineMs < 0

    /** Результат по сервису: свой, а если нет — старый, записанный под кодом встроенного сервиса с тем же адресом. */
    fun ms(s: CheckService): Long? = services[s.id] ?: s.legacyId.takeIf { it.isNotEmpty() }?.let { services[it] }

    fun status(s: CheckService): ServiceStatus = status(ms(s))

    private fun status(ms: Long?): ServiceStatus {
        ms ?: return ServiceStatus.UNKNOWN
        return when {
            ms >= 0 -> ServiceStatus.OPEN
            ms == CheckServices.PARTIAL -> ServiceStatus.PARTIAL
            else -> ServiceStatus.CLOSED
        }
    }

    /** Состояние одного сервиса для таблицы «цель × сервис». */
    fun status(serviceId: String): ServiceStatus {
        val ms = services[serviceId] ?: return ServiceStatus.UNKNOWN
        return when {
            ms >= 0 -> ServiceStatus.OPEN
            ms == CheckServices.PARTIAL -> ServiceStatus.PARTIAL
            else -> ServiceStatus.CLOSED
        }
    }

    /** Адрес сервера сменился после проверки (конфиг найден по имени). */
    fun staleFor(link: String): Boolean {
        val now = ConfigIdentity.address(link)
        return host.isNotEmpty() && now.isNotEmpty() && host != now
    }

    /** «340 мс · 12.4 Мбит/с» (что известно). */
    fun speedText(): String {
        val parts = ArrayList<String>()
        if (baselineMs >= 0) parts += "$baselineMs мс"
        if (mbps >= 0) parts += SpeedTest.format(mbps)
        return parts.joinToString(" · ")
    }

    /** Подпись с названиями: «Claude ✓ · Qwen ≈ · YouTube ✗» — понятнее кодов; «?» — ещё не проверялся. */
    fun labelNamed(selected: List<CheckService>, odds: ((String) -> Int?)? = null): String = selected.joinToString(" · ") { s ->
        val ms = ms(s)
        s.title + " " + (odds?.invoke(s.id)?.let { "$it% " } ?: "") + when {
            ms == null -> "?"
            ms >= 0 -> "✓"
            ms == CheckServices.PARTIAL -> "≈"
            else -> "✗"
        }
    }

    fun label(selected: List<CheckService>): String = when (baselineMs) {
        CheckServices.UNSUPPORTED -> "не поддерживается"
        CheckServices.FAILED -> "не отвечает"
        else -> selected.joinToString(" ") { s ->
            val ms = ms(s)
            s.short + when {
                ms == null -> "?"
                ms >= 0 -> "✓"
                ms == CheckServices.PARTIAL -> "≈"
                else -> "✗"
            }
        }
    }
}
