package com.phoenix.app

/**
 * Карантин серверов, на которых оборвалась сессия.
 *  - Первый и второй провал: [MINUTES] минут сервер идёт в конец очереди, потом получает второй шанс.
 *  - Третий провал за [STRIKE_WINDOW_HOURS] часов: карантин на [LONG_HOURS] часов. Такой сервер остаётся в самом конце очереди
 *    и не возвращается первым, даже когда остальные не нашлись ([releaseAll] снимает только короткий карантин).
 *  - Провал при мёртвой сети не считается: сервер тут ни при чём ([add] с networkAlive = false ничего не делает).
 *  - Успешное подключение снимает один провал ([forgive]); ручная смена провалом не считается ([skip]).
 *  - Хранится в файле ([attach]): перезапуск службы или процесса карантин не сбрасывает.
 */
object Quarantine {
    const val MINUTES = 10L
    /** Сервер, упавший почти сразу после выбора (журнал 21.11: через 8–71 с), не берём час. */
    const val EARLY_MINUTES = 60L
    const val LONG_HOURS = 24L
    const val STRIKES_FOR_LONG = 3
    const val STRIKE_WINDOW_HOURS = 24L
    /** Повторный [add] по тому же серверу в течение этого времени — тот же эпизод сбоя: страйк не добавляется (проверка связи и перебор оба винят один обрыв). */
    const val SAME_EPISODE_MS = 120_000L

    private val lock = Any()
    private val until = HashMap<String, Long>()
    private val long = HashSet<String>()
    private val strikes = HashMap<String, MutableList<Long>>()
    private val lastStrikeAt = HashMap<String, Long>()
    @Volatile private var file: java.io.File? = null

    /** Подключает файл хранения и подгружает из него ещё действующий карантин; повторный вызов с тем же файлом ничего не ломает. */
    fun attach(f: java.io.File, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (file?.path == f.path) return
            file = f
            try {
                if (f.exists()) restore(f.readText(), now)
            } catch (_: Throwable) {
            }
        }
    }

    fun add(identity: String, now: Long = System.currentTimeMillis(), networkAlive: Boolean = true, earlyFall: Boolean = false) {
        if (identity.isEmpty() || !networkAlive || identity.contains('\t') || identity.contains('\n')) return
        synchronized(lock) {
            val minutes = if (earlyFall) EARLY_MINUTES else MINUTES
            val prev = lastStrikeAt[identity]
            if (prev != null && now - prev in 0 until SAME_EPISODE_MS) {
                // тот же эпизод (проверка связи и перебор винят один обрыв): страйк уже записан, срок только продлевается (час «упал сразу» не сбивается на 10 минут)
                if (identity !in long) until[identity] = maxOf(until[identity] ?: 0L, now + minutes * 60_000L)
                save(now)
                return
            }
            lastStrikeAt[identity] = now
            val list = strikes.getOrPut(identity) { ArrayList() }
            list.removeAll { now - it > STRIKE_WINDOW_HOURS * 3_600_000L }
            list.add(now)
            if (list.size >= STRIKES_FOR_LONG) {
                until[identity] = now + LONG_HOURS * 3_600_000L
                long.add(identity)
            } else {
                until[identity] = maxOf(until[identity] ?: 0L, now + minutes * 60_000L)
                long.remove(identity)
            }
            save(now)
        }
    }

    /**
     * Сервер, с которого ушли по просьбе (кнопка «Сменить сервер», умная смена при рабочем туннеле): не виноват, страйка нет;
     * на [MINUTES] минут просто не берём его первым (чтобы не вернуться на него же сразу).
     */
    fun skip(identity: String, now: Long = System.currentTimeMillis()) {
        if (identity.isEmpty() || identity.contains('\t') || identity.contains('\n')) return
        synchronized(lock) {
            if (identity in long) return
            until[identity] = maxOf(until[identity] ?: 0L, now + MINUTES * 60_000L)
            save(now)
        }
    }

    fun active(identity: String, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val t = until[identity] ?: return false
            if (now >= t) { until.remove(identity); long.remove(identity); return false }
            return true
        }
    }

    /** Сервер в длинном карантине (сутки). */
    fun isLong(identity: String, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) { return active(identity, now) && identity in long }
    }

    /**
     * Сервер снова подтвердил работу: снимается один (последний) провал и короткий карантин. Остальные остаются: сервер, который то
     * работает, то падает, как раз и должен набрать три провала за сутки (раньше удача стирала все, и суточный карантин не наступал).
     */
    fun forgive(identity: String) {
        synchronized(lock) {
            val list = strikes[identity]
            val had = list != null && list.isNotEmpty()
            if (had) list!!.removeAt(list.size - 1)
            if (list != null && list.isEmpty()) strikes.remove(identity)
            lastStrikeAt.remove(identity)
            val q = if ((strikes[identity]?.size ?: 0) < STRIKES_FOR_LONG) {
                long.remove(identity)
                until.remove(identity) != null
            } else false
            if (had || q) save()
        }
    }

    /**
     * Аннулирует провалы, записанные с момента [since]: проверка шла в режиме, взятом по догадке, а блокировка оказалась другой
     * (или сеть сменилась), и эти серверы ни в чём не виноваты. Прежние провалы остаются. Возвращает, у скольких серверов что-то снято.
     */
    fun annulSince(since: Long, now: Long = System.currentTimeMillis()): Int {
        if (since <= 0L) return 0
        var n = 0
        synchronized(lock) {
            for ((id, list) in strikes.entries.toList()) {
                val before = list.size
                list.removeAll { it >= since }
                if (list.size == before) continue
                n++
                if (list.isEmpty()) {
                    strikes.remove(id); until.remove(id); long.remove(id)
                } else {
                    if (list.size < STRIKES_FOR_LONG) long.remove(id)
                    val t = list.max() + MINUTES * 60_000L
                    if (!long.contains(id)) { if (t > now) until[id] = t else until.remove(id) }
                }
            }
            if (n > 0) save(now)
        }
        return n
    }

    fun clear() = synchronized(lock) { until.clear(); long.clear(); strikes.clear(); save() }

    /** Сколько серверов сейчас в карантине. */
    fun count(now: Long = System.currentTimeMillis()): Int = synchronized(lock) { until.entries.count { it.value > now } }

    /** Снять короткий карантин: другие серверы не нашлись, значит такие надо проверить первыми. Длинный (сутки) остаётся. Возвращает, сколько снято. */
    fun releaseAll(now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val ids = until.entries.filter { it.value > now && it.key !in long }.map { it.key }
        ids.forEach { until.remove(it) }
        if (ids.isNotEmpty()) save(now)
        ids.size
    }

    /** Текст для файла: «идентификатор TAB до TAB 1/0 TAB метки провалов через запятую». */
    internal fun serialize(now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val ids = (until.keys + strikes.keys).toSortedSet()
        ids.joinToString("\n") { id ->
            val u = until[id]?.takeIf { it > now } ?: 0L
            val st = (strikes[id] ?: emptyList<Long>()).filter { now - it <= STRIKE_WINDOW_HOURS * 3_600_000L }
            id + "\t" + u + "\t" + (if (id in long && u > 0) "1" else "0") + "\t" + st.joinToString(",")
        }
    }

    internal fun restore(text: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            // сливаем с тем, что уже есть в памяти (служба могла записать провал раньше, чем подгрузился файл)
            for (line in text.lineSequence()) {
                val p = line.split('\t')
                if (p.size < 4 || p[0].isEmpty()) continue
                val u = p[1].toLongOrNull() ?: 0L
                if (u > now && u > (until[p[0]] ?: 0L)) { until[p[0]] = u; if (p[2] == "1") long.add(p[0]) }
                val st = p[3].split(',').mapNotNull { it.toLongOrNull() }.filter { now - it <= STRIKE_WINDOW_HOURS * 3_600_000L }
                if (st.isNotEmpty()) strikes[p[0]] = ((strikes[p[0]] ?: mutableListOf<Long>()) + st).distinct().sorted().toMutableList()
            }
        }
    }

    internal fun resetForTest() = synchronized(lock) { until.clear(); long.clear(); strikes.clear(); lastStrikeAt.clear(); file = null }

    private fun save(now: Long = System.currentTimeMillis()) {
        val f = file ?: return
        try {
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(serialize(now))
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Throwable) {
        }
    }
}
