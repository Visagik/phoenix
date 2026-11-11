package com.phoenix.app

/** «Auto → Оптимальная» и подобные — не конкретный сервер, а выбор самой подписки: годятся как запасные, но не как первый выбор для цели. */
object ServerKind {
    private val AUTO = Regex("^\\s*(auto\\b|авто\\b)|оптимальн|🚀", RegexOption.IGNORE_CASE)

    fun isAuto(name: String): Boolean = AUTO.containsMatchIn(name)

    /** Конкретные серверы вперёд, «Auto» в конец; порядок внутри групп сохраняется. */
    fun concreteFirst(list: List<Candidate>, nameOf: (Candidate) -> String): List<Candidate> {
        val (auto, concrete) = list.partition { isAuto(nameOf(it)) }
        return concrete + auto
    }
}
