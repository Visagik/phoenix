package com.phoenix.app

import java.time.LocalDate

/** Трекер стабильности: сколько разных дней встречалось имя профиля. */
class Tracker(private val storage: Storage) {

    data class Entry(
        val name: String,
        val days: Int,
        val lastSeen: String,
        val services: String,
        val occ: Int,
    )

    fun load(): MutableMap<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        if (!storage.stableFile.exists()) return map
        storage.stableFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3 && p[0].isNotBlank()) {
                map[p[0]] = Entry(
                    name = p[0],
                    days = p[1].toIntOrNull() ?: 1,
                    lastSeen = p[2],
                    services = p.getOrElse(3) { "" },
                    occ = p.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
                )
            }
        }
        return map
    }

    /** @return краткая сводка для лога */
    fun update(items: List<ParsedLink>, today: String): String {
        val db = load()

        val todayOcc = HashMap<String, Int>()
        for (p in items) {
            // title = #имя, а у vmess без #имени — поле ps из base64-JSON
            if (p.title.isEmpty()) continue
            val key = p.title.replace('\t', ' ')
            todayOcc[key] = (todayOcc[key] ?: 0) + 1
        }

        var newNames = 0
        for ((name, occ) in todayOcc) {
            val old = db[name]
            db[name] = if (old == null) {
                newNames++
                Entry(name, 1, today, "", occ)
            } else {
                Entry(name, if (old.lastSeen != today) old.days + 1 else old.days, today, old.services, occ)
            }
        }
        // не виденные сегодня: occ = 0
        for ((name, e) in db.entries.toList()) {
            if (name !in todayOcc) db[name] = e.copy(occ = 0)
        }

        // забываем имена, которых не было больше 30 дней
        val limit = LocalDate.parse(today).minusDays(30)
        db.entries.removeAll { (_, e) ->
            try {
                LocalDate.parse(e.lastSeen).isBefore(limit)
            } catch (_: Exception) {
                false
            }
        }

        val sb = StringBuilder()
        for (e in db.values) {
            sb.append(e.name).append('\t').append(e.days).append('\t').append(e.lastSeen).append('\t')
                .append(e.services).append('\t').append(e.occ).append('\n')
        }
        storage.stableFile.writeTextAtomic(sb.toString())

        val stable = db.values.count { it.days >= 3 }
        return "новых имён: $newNames, всего: ${db.size}, стабильных (3+ дня): $stable"
    }
}
