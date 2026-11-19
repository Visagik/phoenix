package com.phoenix.app

/**
 * Пожелания пользователя к выбору сервера: страны, которые предпочитать, страны и отдельные серверы, к которым не подключаться.
 * Применяется в [Ranking.candidates] и [Ranking.rank], поэтому действует на все пути выбора: главную, смену, поиск при обрыве.
 * Если из-за ограничений не осталось ни одного живого сервера, ограничения временно игнорируются ([relaxed]) — связь важнее.
 */
object PoolPolicy {
    @Volatile var prefer: Set<String> = emptySet()
        private set
    @Volatile var avoid: Set<String> = emptySet()
        private set
    @Volatile var hidden: Set<String> = emptySet()
        private set

    /** Последний вызов [apply] снял ограничения, потому что иначе серверов не осталось. */
    @Volatile var relaxed: Boolean = false
        private set

    fun configure(prefer: Set<String>, avoid: Set<String>, hidden: Set<String>) {
        this.prefer = prefer.map { it.uppercase() }.filter { it.length == 2 }.toSet()
        this.avoid = avoid.map { it.uppercase() }.filter { it.length == 2 }.toSet() - this.prefer
        this.hidden = hidden
        relaxed = false
    }

    fun isEmpty(): Boolean = prefer.isEmpty() && avoid.isEmpty() && hidden.isEmpty()

    fun preferred(c: Candidate): Boolean = c.cc.isNotEmpty() && c.cc.uppercase() in prefer

    fun blocked(c: Candidate): Boolean = c.identity in hidden || (c.cc.isNotEmpty() && c.cc.uppercase() in avoid)

    fun apply(list: List<Candidate>): List<Candidate> {
        if (isEmpty() || list.isEmpty()) { relaxed = false; return list }
        val kept = list.filterNot { blocked(it) }
        val noneAlive = kept.none { it.alive } && list.any { it.alive }
        if (kept.isEmpty() || noneAlive) { relaxed = true; return list }
        relaxed = false
        return kept
    }

    /**
     * Серверы из источников с приоритетом «Только если ничего нет» ([SourcePriority.RESERVE]) выбираются, лишь когда ни один другой
     * сервер не жив. Пока остальные есть и отвечают, запасные в выбор не попадают.
     */
    fun applyReserve(list: List<Candidate>): List<Candidate> {
        val reserve = list.filter { it.prio <= SourcePriority.RESERVE }
        if (reserve.isEmpty()) return list
        val main = list.filter { it.prio > SourcePriority.RESERVE }
        return if (main.any { it.alive }) main else list
    }

    /** Список для хранения: по одному в строке. */
    fun encode(items: Set<String>): String = items.filter { it.isNotBlank() && !it.contains('\n') }.sorted().joinToString("\n")

    fun decode(text: String?): Set<String> = (text ?: "").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
