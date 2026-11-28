package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Некоторые подписки сами помечают конфиги в названии («[📃 Белые списки]», «whitelist»). Для режима, которому метка
 * не соответствует, такие конфиги не исключаются, а пробуются после остальных.
 */
object ListHint {
    private val white = Regex("(?iu)бел(ые|ый|ых|ого|ым)\\s+списк|white[\\s_-]?list")
    private val black = Regex("(?iu)ч[её]рн(ые|ый|ых|ого|ым)\\s+списк|black[\\s_-]?list")

    private val mixed = Regex("(?iu)бел\\S*.*ч[её]рн|ч[её]рн\\S*.*бел")

    private val special = Regex("(?iu)госуслуг|торрент|torrent|банк\\b|сбер|тинькофф|яндекс|\\bвк\\b|\\bvk\\b|кинопоиск")
    private val russian = Regex("(?iu)\\bru(ssia)?\\b|росси|🇷🇺")

    /** Название говорит, что сервер сделан для одной задачи (госуслуги, торренты, российские сервисы). */
    fun special(name: String): Boolean = special.containsMatchIn(name)

    /** В названии указана Россия. */
    fun russian(name: String): Boolean = russian.containsMatchIn(name)

    fun of(name: String): Mode? = when {
        mixed.containsMatchIn(name) -> null
        white.containsMatchIn(name) && !black.containsMatchIn(name) -> Mode.WHITELIST
        black.containsMatchIn(name) && !white.containsMatchIn(name) -> Mode.BLACKLIST
        else -> null
    }
}
