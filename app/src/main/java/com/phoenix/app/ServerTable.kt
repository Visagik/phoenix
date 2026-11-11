package com.phoenix.app

/** Одна строка таблицы серверов: все показатели в одном месте, без зависимости от Android. */
data class ServerEntry(
    val raw: String,
    val name: String,
    val protocol: String,
    val reality: Boolean,
    val cc: String?,
    val days: Int,
    /** Задержка базовой проверки, мс; null — не проверялся. */
    val pingMs: Long?,
    /** Скорость, Мбит/с; null — не измерялась. */
    val mbps: Double?,
    /** Сколько проверенных сервисов открылось полностью / частично / не открылось / неизвестно. */
    val ok: Int,
    val partial: Int,
    val blocked: Int,
    val unknown: Int,
    /** Дата последней проверки (гггг-мм-дд) или пусто. */
    val checked: String,
    val dead: Boolean,
    val stale: Boolean = false,
) {
    val stable: Boolean get() = days >= 3
    val checkedOnce: Boolean get() = checked.isNotEmpty()
}

enum class ServerSort(val title: String) {
    BEST("Лучшие сверху"),
    PING("Задержка"),
    SPEED("Скорость"),
    SERVICES("Открытые сервисы"),
    DAYS("Дни жизни"),
    CHECKED("Дата проверки"),
    COUNTRY("Страна"),
    PROTOCOL("Протокол"),
    NAME("Имя"),
}

data class ServerFilter(
    val query: String = "",
    val onlyAlive: Boolean = false,
    val onlyStable: Boolean = false,
    val onlyChecked: Boolean = false,
    val protocol: String = "",
    val country: String = "",
    /** Нужно минимум столько полностью открытых сервисов. */
    val minOk: Int = 0,
)

object ServerTable {
    fun matches(e: ServerEntry, f: ServerFilter): Boolean {
        if (f.onlyAlive && (e.dead || !e.checkedOnce)) return false
        if (f.onlyStable && !e.stable) return false
        if (f.onlyChecked && !e.checkedOnce) return false
        if (f.protocol.isNotEmpty() && !e.protocol.equals(f.protocol, ignoreCase = true)) return false
        if (f.country.isNotEmpty() && !(e.cc ?: "??").equals(f.country, ignoreCase = true)) return false
        if (e.ok < f.minOk) return false
        val q = f.query.trim()
        if (q.isNotEmpty() && !e.name.contains(q, ignoreCase = true) && !(e.cc ?: "").equals(q, ignoreCase = true)) return false
        return true
    }

    /** Направление по умолчанию: что лучше при большем значении — по убыванию, задержка и текст — по возрастанию. */
    fun defaultDesc(by: ServerSort): Boolean = when (by) {
        ServerSort.PING, ServerSort.COUNTRY, ServerSort.PROTOCOL, ServerSort.NAME -> false
        else -> true
    }

    /**
     * Сортировка. Строки без нужного показателя всегда внизу (и при «по убыванию» тоже),
     * чтобы непроверенные серверы не вылезали наверх. [desc] разворачивает только известные значения.
     */
    fun sort(list: List<ServerEntry>, by: ServerSort, desc: Boolean? = null): List<ServerEntry> {
        // По умолчанию: что-то лучше при большем значении — по убыванию, задержка и имя — по возрастанию.
        val down = desc ?: defaultDesc(by)
        fun <T : Comparable<T>> cmp(key: (ServerEntry) -> T?): Comparator<ServerEntry> = Comparator { a, b ->
            val x = key(a)
            val y = key(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> if (down) y.compareTo(x) else x.compareTo(y)
            }
        }
        val c: Comparator<ServerEntry> = when (by) {
            ServerSort.BEST -> return best(list)
            ServerSort.PING -> cmp { if (it.dead) null else it.pingMs }
            ServerSort.SPEED -> cmp { if (it.dead) null else it.mbps }
            ServerSort.SERVICES -> cmp { if (it.checkedOnce && !it.dead) it.ok * 2 + it.partial else null }
            ServerSort.DAYS -> cmp<Int> { it.days }
            ServerSort.CHECKED -> cmp { it.checked.ifEmpty { null } }
            ServerSort.COUNTRY -> cmp { it.cc }
            ServerSort.PROTOCOL -> cmp<String> { it.protocol.lowercase() }
            ServerSort.NAME -> cmp<String> { it.name.lowercase() }
        }
        return list.sortedWith(c.then(compareBy { it.name.lowercase() }))
    }

    /** «Лучшие»: живые, потом больше открытых сервисов, быстрее, стабильнее; мёртвые и непроверенные внизу. */
    fun best(list: List<ServerEntry>): List<ServerEntry> = list.sortedWith(
        compareBy<ServerEntry> { if (it.dead) 2 else if (!it.checkedOnce) 1 else 0 }
            .thenByDescending { it.ok * 2 + it.partial }
            .thenByDescending { it.mbps ?: -1.0 }
            .thenBy { it.pingMs ?: Long.MAX_VALUE }
            .thenByDescending { it.days }
            .thenBy { it.name.lowercase() },
    )

    fun apply(list: List<ServerEntry>, f: ServerFilter, by: ServerSort, desc: Boolean? = null): List<ServerEntry> =
        sort(list.filter { matches(it, f) }, by, desc)

    /** Страны для выбора в фильтре: по убыванию числа серверов. */
    fun countries(list: List<ServerEntry>): List<String> =
        list.groupingBy { it.cc ?: "??" }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    fun protocols(list: List<ServerEntry>): List<String> =
        list.map { it.protocol.lowercase() }.distinct().sorted()

    /** Краткая сводка для шапки: «Всего 840 · проверено 312 · живых 97». */
    fun summary(list: List<ServerEntry>): String {
        val checked = list.count { it.checkedOnce }
        val alive = list.count { it.checkedOnce && !it.dead }
        return "Всего ${list.size} · проверено $checked · живых $alive"
    }

    /** Строка показателей для карточки. */
    fun metrics(e: ServerEntry): String {
        val parts = ArrayList<String>()
        parts += e.protocol.uppercase() + if (e.reality) " Reality" else ""
        parts += e.cc ?: "??"
        parts += "${e.days} дн."
        if (e.dead) parts += "не отвечает"
        else {
            e.pingMs?.let { parts += "$it мс" }
            e.mbps?.let { parts += String.format(java.util.Locale.US, "%.1f Мбит/с", it) }
            if (e.checkedOnce) parts += "сервисов: ${e.ok}✓" + (if (e.partial > 0) " ${e.partial}≈" else "") + (if (e.blocked > 0) " ${e.blocked}✗" else "")
        }
        if (e.checkedOnce) parts += "проверен " + e.checked.takeLast(5)
        return parts.joinToString(" · ")
    }
}
