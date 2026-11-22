package com.phoenix.app

import java.io.File

/**
 * Подсказка режима по сети. Вид блокировок определяется долго (замер до 14 с, подтверждение до минуты), а сеть почти всегда та же,
 * что была раньше: дома — чёрные списки, у оператора в дороге — белые. Поэтому для сети с понятным названием («wifi», «mobile:мтс»)
 * помним режим, который в ней подтверждался, и берём его сразу при смене сети. Определение потом подтверждает или возвращает.
 */
object ModeHint {
    /** Сколько подтверждений в этой сети нужно, чтобы доверять подсказке. */
    const val NEED = 2
    const val MAX_COUNT = 50
    const val MAX_NETS = 30

    data class Entry(val mode: Mode, val count: Int)

    fun parse(text: String?): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (line in (text ?: "").lineSequence()) {
            val p = line.split('\t')
            if (p.size < 3) continue
            val mode = Mode.entries.firstOrNull { it.name == p[1] } ?: continue
            val n = p[2].toIntOrNull() ?: continue
            if (p[0].isNotEmpty()) out[p[0]] = Entry(mode, n.coerceIn(1, MAX_COUNT))
        }
        return out
    }

    fun render(map: Map<String, Entry>): String =
        map.entries.joinToString("\n") { (k, v) -> "$k\t${v.mode.name}\t${v.count}" } + "\n"

    /** Определение подтвердило режим [mode] в сети [label]; тот же режим копит счёт, другой начинает с единицы. */
    fun note(map: Map<String, Entry>, label: String, mode: Mode): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap(map)
        if (label.isEmpty() || label == "none") return out
        val old = out.remove(label)
        out[label] = Entry(mode, if (old?.mode == mode) minOf(old.count + 1, MAX_COUNT) else 1)
        while (out.size > MAX_NETS) out.remove(out.keys.first())
        return out
    }

    /** Режим, на который стоит перейти сразу, или null (нет подсказки, она не подтверждена, совпадает с текущим). */
    fun guess(map: Map<String, Entry>, label: String, current: Mode): Mode? {
        val e = map[label] ?: return null
        return if (e.count >= NEED && e.mode != current) e.mode else null
    }

    /** Результат замера относится к другой сети, чем та, где он начинался: голосов и выводов по нему не делаем. */
    fun stale(startLabel: String, endLabel: String): Boolean = startLabel != endLabel

    fun load(file: File): LinkedHashMap<String, Entry> = try {
        parse(file.takeIf { it.exists() }?.readText())
    } catch (_: Exception) {
        LinkedHashMap()
    }

    fun save(file: File, map: Map<String, Entry>) {
        try { file.writeTextAtomic(render(map)) } catch (_: Exception) { }
    }
}

/**
 * Режим, взятый по подсказке до подтверждения. Если сеть сменилась снова, а подтверждения не было, возвращаем прежний:
 * то, что считалось в «чужой» сети, ей не принадлежит.
 */
class ProvisionalMode {
    private var from: Mode? = null
    private var net: String = ""

    /** Когда режим взят по подсказке: всё, что проверка серверов записала с этого момента, зависит от догадки. */
    @Volatile var since: Long = 0L
        private set

    val active: Boolean get() = from != null

    fun start(previous: Mode, label: String, now: Long = System.currentTimeMillis()) { from = previous; net = label; since = now }

    fun confirm() { from = null; net = "" }

    /** Сеть теперь [label]; если подсказка была взята для другой сети и не подтвердилась — режим, на который надо вернуться. */
    fun revertOnNetChange(label: String): Mode? {
        val f = from ?: return null
        if (label == net) return null
        from = null; net = ""
        return f
    }
}
