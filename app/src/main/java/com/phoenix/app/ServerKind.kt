package com.phoenix.app

/** «Auto → Оптимальная» и подобные — не конкретный сервер, а выбор самой подписки: годятся как запасные, но не как первый выбор для цели. */
object ServerKind {
    private val AUTO = Regex("^\\s*(auto\\b|авто\\b)|оптимальн|🚀", RegexOption.IGNORE_CASE)

    fun isAuto(name: String): Boolean = AUTO.containsMatchIn(name)

    /**
     * Сервер подписки, у которого страна выхода выбирается на стороне подписки и плавает: «Auto», «Оптимальная» и «🤖 AI».
     * Для сервисов с проверкой региона (NotebookLM, Gemini) такой сервер не годится: проверка цели его пропускала, а в работе он
     * выходил из Германии, где сервис закрыт (журнал 19.11: Notebook на «🤖 AI», цель «рабочая», страница «не поддерживается»).
     */
    fun isFloatingExit(name: String): Boolean = isAuto(name) || name.contains("🤖")

    /** Конкретные серверы вперёд, «Auto» в конец; порядок внутри групп сохраняется. */
    fun concreteFirst(list: List<Candidate>, nameOf: (Candidate) -> String): List<Candidate> {
        val (auto, concrete) = list.partition { isAuto(nameOf(it)) }
        return concrete + auto
    }
}
