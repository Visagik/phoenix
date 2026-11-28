package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Сервисы, построенные из целей: колонка = цель, адрес проверки — адрес цели, подпись — сокращение названия. */
object TargetServices {
    fun from(targets: List<Target>): List<CheckService> {
        val uniq = targets.filter { it.checkUrl.isNotBlank() }.distinctBy { it.checkUrl }
        val shorts = TargetAbbrev.codes(uniq.map { it.label })
        return uniq.mapIndexed { i, t ->
            CheckService(t.id, t.label, t.checkUrl, shorts[i], CheckServices.ALL.firstOrNull { it.url == t.checkUrl }?.id.orEmpty())
        }
    }
}

/**
 * Короткая подпись названия цели: две и больше части (по пробелам и заглавным буквам) — первые буквы первых двух (YouTube → YT),
 * одна часть — первые две буквы (Steam → ST). Совпали — у совпавших три буквы, потом цифра.
 */
object TargetAbbrev {
    fun of(label: String): String {
        val clean = label.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        if (clean.isEmpty()) return "?"
        val parts = ArrayList<String>()
        for (word in clean.split(' ').filter { it.isNotEmpty() }) {
            var cur = StringBuilder()
            for ((i, ch) in word.withIndex()) {
                val boundary = i > 0 && ch.isUpperCase() && !word[i - 1].isUpperCase()
                if (boundary && cur.isNotEmpty()) { parts += cur.toString(); cur = StringBuilder() }
                cur.append(ch)
            }
            if (cur.isNotEmpty()) parts += cur.toString()
        }
        ALIASES[clean.replace(" ", "").lowercase()]?.let { return it }
        val code = if (parts.size >= 2) parts[0].take(1) + parts[1].take(1) else firstAndConsonant(clean.replace(" ", ""))
        return code.uppercase()
    }

    /** Привычные коды известных сервисов: узнаются сразу (TG, DC, WA…). */
    private val ALIASES = mapOf(
        // мессенджеры и соцсети
        "telegram" to "TG", "telegramx" to "TG", "discord" to "DC", "whatsapp" to "WA", "instagram" to "IG", "facebook" to "FB",
        "twitter" to "TW", "x" to "X", "tiktok" to "TT", "snapchat" to "SC", "pinterest" to "PI", "reddit" to "RD", "linkedin" to "LI",
        "viber" to "VB", "signal" to "SG", "skype" to "SK", "zoom" to "ZM", "twitch" to "TV", "threads" to "TH", "vk" to "VK", "вконтакте" to "VK",
        // видео и музыка
        "youtube" to "YT", "youtubemusic" to "YM", "spotify" to "SP", "netflix" to "NF", "soundcloud" to "SC2", "kinopoisk" to "KP", "кинопоиск" to "KP",
        // нейросети
        "claude" to "CL", "chatgpt" to "GPT", "gpt" to "GPT", "openai" to "OA", "gemini" to "GM", "copilot" to "CP", "grok" to "GK", "deepseek" to "DS",
        "qwen" to "QW", "kimi" to "KM", "mistral" to "MS", "perplexity" to "PX", "midjourney" to "MJ", "notebooklm" to "NL", "notebook" to "NB",
        "anthropic" to "AN", "metaai" to "MA", "suno" to "SN", "sora" to "SR",
        // Google и Microsoft
        "google" to "GO", "gmail" to "GML", "googlemaps" to "MP", "maps" to "MP", "googledrive" to "GD", "drive" to "GD", "googlephotos" to "PH",
        "outlook" to "OL", "teams" to "MT", "onedrive" to "OD", "chrome" to "CR", "edge" to "ED", "firefox" to "FF", "opera" to "OP",
        // игры, магазины, прочее
        "steam" to "ST", "epicgames" to "EG", "roblox" to "RB", "minecraft" to "MC", "battlenet" to "BN", "amazon" to "AZ", "aliexpress" to "AE",
        "github" to "GH", "wikipedia" to "WK", "dropbox" to "DB", "notion" to "NO", "figma" to "FG", "canva" to "CV", "obsidian" to "OB",
        "яндекс" to "YA", "yandex" to "YA", "сбер" to "SB", "sber" to "SB", "тинькофф" to "TB", "tinkoff" to "TB", "ozon" to "OZ", "озон" to "OZ",
    )

    private val VOWELS = "aeiouyаеёиоуыэюя"

    /** Первая буква и следующая согласная: Starlink → ST, Gmail → GM; нет согласной — вторая буква. */
    private fun firstAndConsonant(word: String): String {
        if (word.length <= 2) return word
        val rest = word.drop(1)
        val c = rest.firstOrNull { it.isLetter() && it.lowercaseChar() !in VOWELS } ?: rest[0]
        return word[0].toString() + c
    }

    private fun long(label: String): String {
        val clean = label.filter { it.isLetterOrDigit() }
        return clean.take(3).uppercase().ifEmpty { "?" }
    }

    fun codes(labels: List<String>): List<String> {
        val base = labels.map { of(it) }
        val step2 = base.mapIndexed { i, c -> if (base.count { it == c } > 1) long(labels[i]) else c }
        val seen = HashMap<String, Int>()
        return step2.mapIndexed { i, c ->
            if (step2.count { it == c } > 1) { val n = (seen[c] ?: 0) + 1; seen[c] = n; c + n } else c
        }
    }
}

/** Сколько колонок целей показывается на главном экране: два ряда по семь. Всё остальное — в окне «Сменить сервер». */
object TargetMatrixLimits {
    const val PER_ROW = 7
    const val MAX = 14
}
