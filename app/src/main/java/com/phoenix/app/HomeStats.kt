package com.phoenix.app

/** Что показывает главная о пуле: сколько запасных и кто лучший сейчас. Считается вне главного потока. */
data class HomeStats(val reserve: Int, val best: String?) {
    companion object {
        val EMPTY = HomeStats(0, null)

        fun build(rows: List<ConfigRow>, checks: Map<String, CheckResult>, link: String, mode: Mode): HomeStats {
            val ranked = Ranking.rank(Ranking.candidates(rows, checks, mode = mode))
            val host = ConfigIdentity.host(link)
            // запасные: проверенные живые конфиги на других серверах (тот же сервер с другим протоколом не считается)
            val reserve = Ranking.distinctServers(ranked)
                .count { c -> c.host != host && checks[CheckStore.keyOf(c.link)]?.let { !it.dead } == true }
            val best = ranked.firstOrNull()?.let { c ->
                val row = rows.firstOrNull { it.raw == c.link }
                val speed = checks[CheckStore.keyOf(c.link)]?.speedText().orEmpty()
                DisplayName.plain(row?.displayName ?: "") + if (speed.isNotBlank()) " · $speed" else ""
            }
            return HomeStats(reserve, best)
        }
    }
}
