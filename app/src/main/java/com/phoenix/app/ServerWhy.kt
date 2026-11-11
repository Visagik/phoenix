package com.phoenix.app

/** «Почему этот сервер?»: из того, что приложение знает о кандидате, — короткие причины «за» и «против». Ничего не считает заново. */
object ServerWhy {
    data class Reasons(val pros: List<String>, val cons: List<String>) {
        val text: String get() = (pros.map { "＋ $it" } + cons.map { "－ $it" }).joinToString("\n")
        val isEmpty: Boolean get() = pros.isEmpty() && cons.isEmpty()
    }

    fun explain(c: Candidate, services: List<CheckService> = emptyList(), check: CheckResult? = null, exitChecked: Boolean = false): Reasons {
        val pros = ArrayList<String>()
        val cons = ArrayList<String>()
        if (c.alive) pros.add("свежая проверка: отвечает" + if (c.latencyMs > 0) " (${c.latencyMs} мс)" else "") else cons.add("свежей проверки нет")
        if (c.mbps > 0) pros.add("скорость ${"%.1f".format(java.util.Locale.US, c.mbps)} Мбит/с")
        when {
            c.bad -> cons.add("в этой сети уже несколько раз подводил")
            c.steady -> pros.add("в этой сети стабилен (≥ 8 удач, надёжность ≥ 90%)")
            c.rel >= 0.75 -> pros.add("в этой сети надёжность ${(c.rel * 100).toInt()}%")
            c.rel <= 0.35 -> cons.add("в этой сети надёжность ${(c.rel * 100).toInt()}%")
        }
        if (c.quarantined) cons.add("недавно оборвалась сессия: временно в конце очереди")
        if (c.deadMark) cons.add("когда-то не отвечал")
        if (c.mismatch) cons.add("по названию предназначен для другого режима")
        if (c.unfit) cons.add("для общего VPN хуже обычных (узкая цель или выход в России)")
        if (c.modeHint) pros.add("название говорит, что сервер для этого режима")
        when {
            c.prio > 0 -> pros.add("из приоритетной ссылки")
            c.prio < 0 -> cons.add("из ссылки с низким приоритетом")
        }
        if (c.protoBucket == 0) pros.add("такой тип конфига в этой сети выручал") else if (c.protoBucket >= 2) cons.add("такой тип конфига в этой сети подводил")
        if (check != null && !check.dead) {
            for (s in services) {
                val ms = check.services[s.id] ?: continue
                when {
                    ms >= 0 -> pros.add("${s.title}: открывается")
                    ms == CheckServices.PARTIAL -> pros.add("${s.title}: отвечает не полностью")
                    else -> cons.add("${s.title}: не открылся")
                }
            }
        }
        if (exitChecked) pros.add("страна выхода подтверждена проверкой")
        return Reasons(pros, cons)
    }
}
