package com.phoenix.app

object Dedup {
    /**
     * Этап 1: точные дубли (по ссылке без #имени). Если у дубля имя есть, а у первого нет — имя подтягивается.
     * Порядок первого появления сохраняется.
     */
    fun clean(lines: List<String>): List<ParsedLink> {
        val byUrl = LinkedHashMap<String, ParsedLink>()
        for (line in lines) {
            val p = LinkParser.parse(line) ?: continue
            val existing = byUrl[p.urlPart]
            if (existing == null) {
                byUrl[p.urlPart] = p
            } else if (existing.name.isEmpty() && p.name.isNotEmpty()) {
                byUrl[p.urlPart] = existing.copy(name = p.name, title = p.title)
            }
        }
        return byUrl.values.toList()
    }

    /**
     * Этап 2: не более [max] вариантов на один host:port, выбираются с наибольшим приоритетом.
     * Итоговый порядок — исходный.
     */
    fun topPerAddress(items: List<ParsedLink>, max: Int): List<ParsedLink> {
        val keep = HashSet<Int>()
        items.withIndex()
            .groupBy { it.value.address }
            .values
            .forEach { group ->
                group
                    .sortedWith(
                        compareByDescending<IndexedValue<ParsedLink>> { it.value.priority }
                            .thenBy { it.index }
                    )
                    .take(max)
                    .forEach { keep.add(it.index) }
            }
        return items.filterIndexed { i, _ -> i in keep }
    }
}
