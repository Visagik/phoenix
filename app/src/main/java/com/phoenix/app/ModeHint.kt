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

    /** Два счётчика на сеть: сколько подтверждений было у белых ([wl]) и у чёрных ([bl]) списков. */
    data class Entry(val wl: Int, val bl: Int) {
        fun of(mode: Mode): Int = if (mode == Mode.WHITELIST) wl else bl
    }

    /** Формат: `метка\tWL\t<wl>\tBL\t<bl>`. Старый `метка\tWHITELIST\t12` читается как wl=12, bl=0. */
    fun parse(text: String?): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (line in (text ?: "").lineSequence()) {
            val p = line.split('\t')
            if (p.size < 3 || p[0].isEmpty()) continue
            if (p.size >= 5 && p[1] == "WL" && p[3] == "BL") {
                val w = p[2].toIntOrNull() ?: continue
                val b = p[4].toIntOrNull() ?: continue
                out[p[0]] = Entry(w.coerceIn(0, MAX_COUNT), b.coerceIn(0, MAX_COUNT))
            } else {
                val mode = Mode.entries.firstOrNull { it.name == p[1] } ?: continue
                val n = (p[2].toIntOrNull() ?: continue).coerceIn(1, MAX_COUNT)
                out[p[0]] = if (mode == Mode.WHITELIST) Entry(n, 0) else Entry(0, n)
            }
        }
        return out
    }

    fun render(map: Map<String, Entry>): String =
        map.entries.joinToString("\n") { (k, v) -> "$k\tWL\t${v.wl}\tBL\t${v.bl}" } + "\n"

    /**
     * Определение подтвердило режим [mode] в сети [label]: его счёт +1 (до 50), счёт другого −1 (не ниже 0).
     * Короткий всплеск (16 минут без белых списков) привычку не стирает; если оператор снял списки насовсем, она сменится за часы.
     */
    fun note(map: Map<String, Entry>, label: String, mode: Mode): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap(map)
        if (label.isEmpty() || label == "none") return out
        val old = out.remove(label) ?: Entry(0, 0)
        out[label] = if (mode == Mode.WHITELIST) Entry(minOf(old.wl + 1, MAX_COUNT), maxOf(old.bl - 1, 0))
        else Entry(maxOf(old.wl - 1, 0), minOf(old.bl + 1, MAX_COUNT))
        while (out.size > MAX_NETS) out.remove(out.keys.first())
        return out
    }

    /** Привычный режим сети: счёт не меньше [NEED] и хотя бы вдвое больше, чем у другого. Иначе null (режимы чередуются — доверять нельзя). */
    fun usual(map: Map<String, Entry>, label: String): Mode? {
        val e = map[label] ?: return null
        return when {
            e.wl >= NEED && e.wl >= 2 * e.bl -> Mode.WHITELIST
            e.bl >= NEED && e.bl >= 2 * e.wl -> Mode.BLACKLIST
            else -> null
        }
    }

    /** Режим, на который стоит перейти сразу, или null (нет подсказки, она не подтверждена, совпадает с текущим). */
    fun guess(map: Map<String, Entry>, label: String, current: Mode): Mode? = usual(map, label)?.takeIf { it != current }

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
