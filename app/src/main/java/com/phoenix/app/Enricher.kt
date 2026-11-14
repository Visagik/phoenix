package com.phoenix.app


/**
 * Гибридное GeoIP-обогащение:
 *  1) страна из названия профиля (NameGeo), результат кэшируется по host;
 *  2) для оставшихся IP — таблица диапазонов в приложении (GeoIpDb), без обращения к сети;
 *  3) к каждой строке добавляется метка "[CC] TG:.. QW:.. CL:.. YT:.. ST:..".
 */
class Enricher(private val storage: Storage) {

    suspend fun enrich(items: List<ParsedLink>): Pair<List<String>, GeoStats> {
        val cache = loadCache()
        val newEntries = StringBuilder()

        // Этап 1: названия
        var fromNames = 0
        for (p in items) {
            val host = p.host ?: continue
            if (host in cache) continue
            val cc = NameGeo.detect(p.title) ?: continue
            cache[host] = cc
            newEntries.append(host).append('\t').append(cc).append('\n')
            fromNames++
        }

        // Этап 2: адреса без страны в названии — по таблице диапазонов внутри приложения (никаких запросов в сеть)
        val ipRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val missing = items.mapNotNull { it.host }.distinct().filter { it !in cache && ipRegex.matches(it) }
        var byTable = 0
        val db = if (missing.isEmpty()) null else GeoIp.load()
        if (db != null) {
            for (ip in missing) {
                val cc = db.lookup(ip) ?: continue
                cache[ip] = cc
                newEntries.append(ip).append('\t').append(cc).append('\n')
                byTable++
            }
        }

        if (newEntries.isNotEmpty()) storage.geoCacheFile.appendText(newEntries.toString())

        // Этап 3: итоговые строки
        var enriched = 0
        var noHost = 0
        var unknown = 0
        val countries = HashMap<String, Int>()
        val out = ArrayList<String>(items.size)
        for (p in items) {
            val base = p.urlPart + if (p.name.isNotEmpty()) "#" + p.name else ""
            val host = p.host
            if (host == null) {
                noHost++
                out += base
                continue
            }
            val cc = cache[host]
            if (cc == null) {
                unknown++
                out += base
                continue
            }
            val s = ServiceBlocks.status(cc)
            val suffix = "[$cc] TG:${s[0]} QW:${s[1]} CL:${s[2]} YT:${s[3]} ST:${s[4]}"
            out += if (p.name.isNotEmpty()) "${p.urlPart}#${p.name} | $suffix" else "${p.urlPart}#$suffix"
            enriched++
            countries[cc] = (countries[cc] ?: 0) + 1
        }

        return out to GeoStats(enriched, noHost, unknown, fromNames, byTable, countries)
    }

    private fun loadCache(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (storage.geoCacheFile.exists()) {
            storage.geoCacheFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].length == 2) map[parts[0]] = parts[1]
            }
        }
        // файл только дописывается и за месяцы разрастался до сотен тысяч строк — держим последние записи (находка №12)
        if (map.size > CACHE_MAX) {
            val keep = map.entries.toList().takeLast(CACHE_KEEP)
            map.clear()
            keep.forEach { map[it.key] = it.value }
            try { storage.geoCacheFile.writeTextAtomic(keep.joinToString("") { it.key + "\t" + it.value + "\n" }) } catch (_: Throwable) { }
        }
        return map
    }

    companion object {
        private const val CACHE_MAX = 20_000
        private const val CACHE_KEEP = 10_000
    }
}
