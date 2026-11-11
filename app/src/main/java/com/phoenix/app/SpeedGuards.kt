package com.phoenix.app

/** Что считать ответом «туннель жив» при проверке здоровья: заглушки провайдера отвечают 200 с HTML вместо пустого 204. */
object HealthVerdict {
    fun ok(url: String, code: Int): Boolean = if (url.contains("generate_204")) code == 204 else code in 200..399
}

/**
 * Шейпинг оператора: после нескольких минут нормальной скорости она резко падает в 2–3 раза, хотя пользуются так же.
 * Детектор только подозревает по пассивным замерам (байты за период); подтверждает отдельный активный замер, потому что
 * скорость падает и просто когда видео дозагрузилось.
 */
class ShapingDetector(private val window: Int = 12) {
    private val rates = ArrayDeque<Double>()

    /** Средняя «хорошая» скорость до падения (Мбит/с); осмысленна, когда [feed] вернул true. */
    var peak = 0.0
        private set

    fun reset() { rates.clear(); peak = 0.0 }

    /** @return true — подозрение: последние два замера в [DROP_FACTOR]+ раз ниже прежних хороших. */
    fun feed(mbps: Double): Boolean {
        rates.addLast(mbps)
        while (rates.size > window) rates.removeFirst()
        if (rates.size < MIN_SAMPLES) return false
        val list = rates.toList()
        val recent = list.takeLast(2)
        val earlier = list.dropLast(2).filter { it >= MIN_HIGH }
        if (earlier.size < MIN_HIGH_SAMPLES) return false
        peak = earlier.average()
        return recent.average() < peak / DROP_FACTOR
    }

    companion object {
        const val MIN_SAMPLES = 6
        const val MIN_HIGH = 3.0
        const val MIN_HIGH_SAMPLES = 3
        const val DROP_FACTOR = 2.5
    }
}

/** «Фокус»: пока открыто приложение из ваших целей, тяжёлые фоновые проверки и смена сервера по скорости ждут. */
object FocusRule {
    fun active(foreground: String?, targetApps: Set<String>, enabled: Boolean = true): Boolean =
        enabled && foreground != null && foreground in targetApps
}
