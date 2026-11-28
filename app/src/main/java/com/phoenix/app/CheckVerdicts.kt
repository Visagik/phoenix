package com.phoenix.app

import java.io.File

/**
 * Ошибка сертификата — это «сервер до сайта дотянулся, но сертификат не принят» (банки, Microsoft и подобные меняют/проверяют сертификаты),
 * а не «сайт закрыт». Обрыв рукопожатия без сертификата сюда не входит.
 */
object TlsOutcome {
    /**
     * Замер прямого доступа (определение режима): российский сайт с сертификатом, которому Android не доверяет (выдан российским
     * удостоверяющим центром), всё равно ответил — «открылся». У зарубежных адресов ошибка сертификата может быть заглушкой провайдера
     * с подменным сертификатом, там оценка не меняется. Доверие к сертификату не добавляется: меняется только оценка «ответил / нет».
     */
    fun answeredDespite(url: String, e: Throwable?): Boolean = url in BlockDetector.RUSSIAN && certProblem(e)

    fun certProblem(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth < 8) {
            if (t is java.security.cert.CertificateException || t is javax.net.ssl.SSLPeerUnverifiedException || t is java.security.cert.CertPathValidatorException) return true
            if (t.javaClass.name.endsWith("CertificateException") || t.javaClass.name.endsWith("CertPathValidatorException")) return true
            t = t.cause
            depth++
        }
        return false
    }
}

/**
 * Решение «2 из 3» для проверки страницы сайта по региону: страница через один и тот же сервер отвечает по-разному от запроса к запросу
 * (журнал 21.11: NotebookLM и Gemini через GB то «✓», то «✗»). Нужны два одинаковых ответа; третье чтение только при расхождении.
 * @param read true — «не поддерживается в стране», false — обычная страница, null — не удалось узнать (голосом не считается)
 * @return решение или null, если двух одинаковых ответов не набралось
 */
object RegionVote {
    fun decide(maxReads: Int, read: () -> Boolean?): Boolean? {
        var yes = 0
        var no = 0
        var reads = 0
        while (reads < maxReads) {
            when (read()) {
                true -> yes++
                false -> no++
                null -> {}
            }
            reads++
            if (yes >= 2) return true
            if (no >= 2) return false
        }
        return if (maxReads == 1) (if (yes == 1) true else if (no == 1) false else null) else null
    }
}

/** Какие HTTP-коды считать «сайт досягаем». Ошибки сервера (5xx) — нет: прокси мог вернуть свою ошибку. */
object Soft {
    fun accepts(code: Int, body: String = ""): Boolean = when {
        code !in 200..499 -> false
        // 403 бывает и от защиты сайта (вопрос-проверка Cloudflare: сервер дотянулся), и от блокировки по стране выхода
        // (российский выход для Claude): засчитываем только явную проверку-челлендж
        code == 403 || code == 451 -> challenge.containsMatchIn(body) && !region.containsMatchIn(body)
        code in 400..499 -> !region.containsMatchIn(body)
        else -> true
    }

    private val challenge = Regex("(?i)just a moment|cf-chl|challenge-platform|enable javascript and cookies")

    private val region = Regex("(?i)not available in your (country|region)|unavailable in your (country|region)|app unavailable|isn't available in your|access denied.{0,40}(country|region)|не доступ\\S* в вашем регионе")

    /** В теле ответа сказано, что сервис закрыт для региона выхода: это не «открылся». */
    fun regionBlocked(body: String): Boolean = region.containsMatchIn(body)
}
