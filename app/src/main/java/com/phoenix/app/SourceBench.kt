package com.phoenix.app

/**
 * «Скамейка запасных»: крупные источники (больше [BIG_SOURCE] конфигов) скачиваются как обычно, но их конфиги перепроверяются, только
 * если среди остальных живых оказалось мало ([MIN_ALIVE]). Свои источники пользователя не откладываются.
 * Конфиг идёт на скамейку, только если все источники, где он встречается, крупные.
 */
object SourceBench {
    const val BIG_SOURCE = 500
    /** Сколько рабочих серверов нужно каждой цели. */
    const val SERVERS_PER_TARGET = 3
    /** Живых на один сервер цели: не каждый живой подходит каждой цели (сервисы закрыты в разных странах). */
    const val SPARE_FACTOR = 3
    /** Потолок нужного числа живых: сколько бы целей ни было, больше этого основной части не требуется. */
    const val NEED_CAP = 100

    /** Сколько живых в основной части нужно для [activeTargets] целей (с потолком [NEED_CAP]). */
    fun aliveNeeded(activeTargets: Int): Int = minOf(activeTargets * SERVERS_PER_TARGET * SPARE_FACTOR, NEED_CAP)

    /** Крупные источники: больше [BIG_SOURCE] конфигов, не свои ссылки и не источники с высоким приоритетом ([high]: пользователь или авто-приоритет считают их лучшими). */
    fun bigUrls(members: Map<String, Set<String>>, own: Set<String>, high: Set<String> = emptySet()): Set<String> =
        members.filter { (url, ids) -> ids.size > BIG_SOURCE && url !in own && url !in high }.keys

    /** Ключи конфигов, которые есть только в крупных источниках. */
    fun benchIds(members: Map<String, Set<String>>, own: Set<String>, high: Set<String> = emptySet()): Set<String> {
        val big = bigUrls(members, own, high)
        if (big.isEmpty()) return emptySet()
        val normal = HashSet<String>()
        for ((url, ids) in members) if (url !in big) normal += ids
        val out = HashSet<String>()
        for (url in big) for (id in members[url].orEmpty()) if (id !in normal) out += id
        return out
    }

    /**
     * Зачем доставать запасных; null — не нужно. Причины (любая): у целей по прошлому подбору не хватило серверов
     * ([shortTargets] — названия целей, у которых рабочих серверов меньше нужного, не считая ушедших напрямую);
     * живых в основной части меньше, чем нужно целям с запасом на несовместимость; сама перепроверка ничего не нашла. Новые причины
     * добавляются сюда же, а не числом-порогом.
     */
    fun reason(aliveMain: Int, activeTargets: Int, shortTargets: List<String>): String? = when {
        aliveMain <= 0 -> "в основных живых нет"
        shortTargets.isNotEmpty() -> "целям не хватило серверов: " + shortTargets.take(5).joinToString(", ") + (if (shortTargets.size > 5) " и ещё ${shortTargets.size - 5}" else "")
        aliveMain < aliveNeeded(activeTargets) -> "живых $aliveMain меньше, чем нужно для $activeTargets целей (${aliveNeeded(activeTargets)})"
        else -> null
    }

    /** Цели, которым при прошлом подборе не хватило рабочих серверов (направленные напрямую не считаются). */
    fun shortOf(targets: List<Target>): List<String> =
        targets.filter { !it.isDirect && it.servers.count { s -> s != Target.DIRECT } < SERVERS_PER_TARGET }.map { it.label }

    /** Цели, которые вообще подбираются (не ушедшие напрямую). */
    fun activeCount(targets: List<Target>): Int = targets.count { !it.isDirect }

    /** Делит список на основной и запасных по ключу [idOf]. */
    fun <T> split(list: List<T>, bench: Set<String>, idOf: (T) -> String): Pair<List<T>, List<T>> =
        if (bench.isEmpty()) list to emptyList() else list.partition { idOf(it) !in bench }
}
