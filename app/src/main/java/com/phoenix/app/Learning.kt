package com.phoenix.app

import java.io.File

/**
 * Лёгкое обучение выбору сервера (3.9.46+): два числа (удачи/неудачи) на тип конфига в связке «сеть × режим»,
 * единая оценка пригодности и теневой режим — оценка считается рядом с действующим рейтингом, ничего не меняя, а расхождения пишутся в журнал.
 */
class Arm(val ok: Int, val fail: Int) {
    val total: Int get() = ok + fail

    /** Доля удач со сглаживанием плюс небольшой бонус за малую изученность (пробуем и малоизвестное). */
    val ucb: Double get() = (ok + 1.0) / (total + 2.0) + EXPLORE / Math.sqrt(total + 1.0)

    fun after(success: Boolean): Arm {
        var o = ok + if (success) 1 else 0
        var f = fail + if (success) 0 else 1
        if (o + f > MAX_TOTAL) { o = (o + 1) / 2; f = (f + 1) / 2 } // старый опыт постепенно забывается
        return Arm(o, f)
    }

    companion object {
        const val EXPLORE = 0.2
        const val MAX_TOTAL = 60
    }
}

class ArmStore(private val file: File) {
    private val arms = LinkedHashMap<String, Arm>()
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L
    private var pending = 0

    companion object {
        const val SAVE_EVERY_MS = 60_000L
        const val SAVE_AFTER_EVENTS = 10
        const val MAX_ARMS = 400
        fun key(net: String, mode: String, link: String) = "${net.replace('\t', ' ')}\t${mode.replace('\t', ' ')}\t${ProtocolLearning.kindOf(link)}"
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ArmStore>()
        fun of(file: File): ArmStore = cache.getOrPut(file.absolutePath) { ArmStore(file) }
    }

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            file.forEachLine { l ->
                val p = l.split('\t')
                val ok = p.getOrNull(3)?.toIntOrNull()
                val f = p.getOrNull(4)?.toIntOrNull()
                if (p.size >= 5 && ok != null && f != null) arms["${p[0]}\t${p[1]}\t${p[2]}"] = Arm(ok, f)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized fun arm(net: String, mode: String, link: String): Arm { load(); return arms[key(net, mode, link)] ?: Arm(0, 0) }

    @Synchronized fun record(net: String, mode: String, link: String, success: Boolean, now: Long) {
        load()
        val k = key(net, mode, link)
        arms.remove(k)?.let { arms[k] = it.after(success) } ?: run { arms[k] = Arm(0, 0).after(success) }
        while (arms.size > MAX_ARMS) arms.remove(arms.keys.first())
        dirty = true
        pending++
        if (now - lastSave >= SAVE_EVERY_MS || pending >= SAVE_AFTER_EVENTS) save(now)
    }

    @Synchronized fun save(now: Long) {
        if (!dirty) return
        try {
            file.writeTextAtomic(arms.entries.joinToString("") { (k, a) -> "$k\t${a.ok}\t${a.fail}\n" })
            dirty = false
            pending = 0
            lastSave = now
        } catch (_: Exception) {
        }
    }
}

/** Единая оценка пригодности кандидата: чем больше, тем лучше. Жёсткие признаки рейтинга — крупные штрафы, мягкие — веса. */
object FitScore {
    fun score(c: Candidate, arm: Arm): Double {
        var s = 0.40 * c.rel + 0.30 * arm.ucb
        s += 0.15 * (Math.min(Math.max(c.mbps, 0.0), 20.0) / 20.0)
        if (c.latencyMs > 0) s += 0.10 * (1.0 - Math.min(c.latencyMs, 1500L) / 1500.0)
        if (c.alive) s += 0.10 else s -= 0.05
        if (c.steady) s += 0.10
        if (c.days >= 3) s += 0.05
        if (c.modeHint) s += 0.08
        s += 0.04 * c.prio
        if (c.bad) s -= 1.0
        if (c.quarantined) s -= 0.8
        if (c.mismatch) s -= 0.5
        if (c.unfit) s -= 0.4
        if (c.deadMark) s -= 0.2
        return s
    }

    fun order(list: List<Candidate>, armOf: (Candidate) -> Arm): List<Candidate> = list.sortedByDescending { score(it, armOf(it)) }
}

/** Теневой режим: что выбрала бы оценка и насколько она согласна с действующим порядком. */
object Shadow {
    data class Verdict(val agrees: Boolean, val actualRankInShadow: Int, val shadowTop: Candidate?, val actualTop: Candidate?)

    fun compare(actual: List<Candidate>, armOf: (Candidate) -> Arm): Verdict {
        val a = actual.firstOrNull()
        val shadow = FitScore.order(actual.take(200), armOf)
        val s = shadow.firstOrNull()
        val rank = if (a == null) -1 else shadow.indexOfFirst { it.link == a.link } + 1
        return Verdict(a != null && s != null && a.identity == s.identity, rank, s, a)
    }
}

/** Повтор записанных решений: по каждому — кандидаты в порядке рейтинга и известный исход «работает ли». Нужен, чтобы оценить правило, не рискуя телефоном. */
object Replay {
    data class Decision(val candidates: List<Candidate>, val works: Set<String>)

    /** Доля решений, где выбранный первым сервер работал. */
    fun successRate(decisions: List<Decision>, pick: (List<Candidate>) -> Candidate?): Double {
        if (decisions.isEmpty()) return 0.0
        return decisions.count { d -> pick(d.candidates)?.identity?.let { it in d.works } == true }.toDouble() / decisions.size
    }
}
