package com.phoenix.app

import java.io.File

/**
 * Круг перепроверки (Н1): система может остановить работу (лимит 10 минут без службы переднего плана, нехватка памяти), а в чёрных списках
 * 7000+ конфигов не проверяются за один раз. Файл `recheck_round.txt` в папке режима хранит время начала круга, сеть и ключи конфигов
 * (CheckStore.keyOf), уже проверенных в этом круге. Следующий запуск в той же сети и в пределах [MAX_AGE_MS] пропускает их и идёт дальше,
 * а не начинает с верха списка. Круг закончился — файл удаляется. Чистая логика.
 */
object RecheckRound {
    const val FILE_NAME = "recheck_round.txt"
    const val MAX_AGE_MS = 6 * 3_600_000L

    data class Round(val startedAt: Long, val net: String, val keys: Set<String>)

    fun encode(r: Round): String = "round\t${r.startedAt}\t${r.net.replace('\t', ' ').replace('\n', ' ')}\n" + r.keys.joinToString("\n") + (if (r.keys.isEmpty()) "" else "\n")

    fun decode(text: String): Round? {
        val lines = text.lines()
        val head = lines.firstOrNull()?.split('\t') ?: return null
        if (head.size < 3 || head[0] != "round") return null
        val at = head[1].toLongOrNull() ?: return null
        return Round(at, head.drop(2).joinToString("\t"), lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    /** Продолжать ли прежний круг: та же сеть и круг моложе [MAX_AGE_MS] (часы назад не ходят: «из будущего» — тоже заново). */
    fun resumable(r: Round?, net: String, now: Long): Boolean = r != null && r.net == net && now >= r.startedAt && now - r.startedAt < MAX_AGE_MS

    /** Кандидаты без уже проверенных в круге; порядок сохраняется. */
    fun <T> remaining(items: List<T>, done: Set<String>, keyOf: (T) -> String): List<T> = if (done.isEmpty()) items else items.filter { keyOf(it) !in done }

    fun load(file: File): Round? = try { if (file.exists()) decode(file.readText()) else null } catch (_: Exception) { null }

    fun exists(file: File): Boolean = file.exists()

    /** Накопитель ключей одного круга; сохраняется при каждом сбросе результатов. Потокобезопасен. */
    class Tracker(private val file: File, startedAt: Long, private val net: String, initial: Set<String>) {
        private val started = startedAt
        // тип — Set, а не KeySetView: методы KeySetView (size, addAll) есть только с API 24, а Set их вызывает через интерфейс
        private val keys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also { it.addAll(initial) }

        val count: Int get() = keys.size

        fun add(more: Collection<String>) { keys.addAll(more) }

        @Synchronized
        fun save() {
            try { file.writeTextAtomic(encode(Round(started, net, keys.toSet()))) } catch (_: Throwable) { }
        }

        fun finish() { try { file.delete() } catch (_: Throwable) { } }
    }

    /** Строка журнала при продолжении круга. */
    fun resumeLine(r: Round, fmt: (Long) -> String): String = "RECHECK: продолжаю круг от ${fmt(r.startedAt)}, уже проверено ${r.keys.size}"
}
