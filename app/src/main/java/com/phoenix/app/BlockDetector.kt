package com.phoenix.app

import java.io.File

enum class BlockType { WHITELIST, BLACKLIST, OFFLINE, UNKNOWN }

/** Сколько адресов из каждой группы открылось напрямую (мимо VPN). */
data class ProbeCounts(val foreignOk: Int, val foreignTotal: Int, val ruOk: Int, val ruTotal: Int)

/**
 * Определение типа блокировок по тому, что открывается напрямую:
 *  - почти все зарубежные сайты открываются — чёрные списки (закрыты отдельные сервисы);
 *  - зарубежные не открываются, а российские открываются — белые списки (доступно только разрешённое);
 *  - не открывается ничего — сети нет.
 */
object BlockDetector {
    /** Нейтральные зарубежные адреса, которые не блокируются по отдельности. */
    val FOREIGN = listOf(
        "https://www.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://www.wikipedia.org/",
        "https://github.com/",
        "https://www.msftconnecttest.com/connecttest.txt",
        "https://captive.apple.com/",
        // не крупные CDN: в белых списках операторы иногда пропускают Google/Cloudflare/Microsoft, а эти нет
        "https://www.python.org/",
        "https://www.debian.org/",
        "https://www.kernel.org/",
        "https://nginx.org/",
        "https://www.php.net/",
    )

    val RUSSIAN = listOf(
        "https://ya.ru/",
        "https://vk.com/",
        "https://www.gosuslugi.ru/",
        "https://www.sberbank.ru/",
    )

    const val FOREIGN_OPEN_RATIO = 0.6
    const val FOREIGN_CLOSED_RATIO = 0.2

    fun classify(p: ProbeCounts): BlockType {
        if (p.foreignTotal == 0 || p.ruTotal == 0) return BlockType.UNKNOWN
        if (p.foreignOk == 0 && p.ruOk == 0) return BlockType.OFFLINE
        val foreign = p.foreignOk.toDouble() / p.foreignTotal
        val ru = p.ruOk.toDouble() / p.ruTotal
        return when {
            foreign >= FOREIGN_OPEN_RATIO -> BlockType.BLACKLIST
            foreign <= FOREIGN_CLOSED_RATIO && ru >= 0.5 -> BlockType.WHITELIST
            else -> BlockType.UNKNOWN
        }
    }

    fun title(t: BlockType): String = when (t) {
        BlockType.WHITELIST -> "белые списки"
        BlockType.BLACKLIST -> "чёрные списки"
        BlockType.OFFLINE -> "нет сети"
        BlockType.UNKNOWN -> "не ясно"
    }
}

/** Накопленные «голоса» последних определений: режим меняется только после нескольких одинаковых подряд. */
data class Vote(val type: BlockType, val count: Int)

/**
 * Ручной выбор режима: пока включено автоопределение, оно за несколько секунд возвращало прежний режим, и выбор «не держался».
 * Поэтому после ручного выбора автоопределение молчит [HOLD_MS] или до смены сети (новая сеть — новая ситуация).
 */
object ModeHold {
    const val HOLD_MS = 30 * 60_000L

    /** Столько одинаковых определений подряд, противоречащих ручному выбору, снимают удержание. */
    const val OVERRIDE_VOTES = 4

    fun overridden(votes: Int): Boolean = votes >= OVERRIDE_VOTES

    fun active(manualAt: Long, manualNet: String, now: Long, currentNet: String): Boolean =
        manualAt > 0L && now - manualAt in 0 until HOLD_MS && (manualNet.isEmpty() || manualNet == currentNet)
}

object ModeDecider {
    const val NEED_IN_ROW = 2

    fun modeOf(t: BlockType): Mode? = when (t) {
        BlockType.WHITELIST -> Mode.WHITELIST
        BlockType.BLACKLIST -> Mode.BLACKLIST
        else -> null
    }

    /** @return новый счётчик и режим, на который нужно переключиться (или null) */
    fun step(vote: Vote, detected: BlockType, current: Mode, need: Int = NEED_IN_ROW): Pair<Vote, Mode?> {
        val target = modeOf(detected) ?: return vote to null // «не ясно» и «нет сети» ничего не меняют
        val next = if (vote.type == detected) Vote(detected, vote.count + 1) else Vote(detected, 1)
        val switchTo = if (target != current && next.count >= need) target else null
        return next to switchTo
    }

    fun load(file: File): Vote {
        val p = try {
            file.takeIf { it.exists() }?.readText()?.trim()?.split(' ')
        } catch (_: Exception) {
            null
        }
        val type = BlockType.entries.firstOrNull { it.name == p?.getOrNull(0) } ?: return Vote(BlockType.UNKNOWN, 0)
        return Vote(type, p?.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun save(file: File, vote: Vote) {
        file.writeTextAtomic("${vote.type.name} ${vote.count}")
    }
}
