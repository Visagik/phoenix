package com.phoenix.app

/**
 * Карантин серверов, на которых оборвалась сессия.
 *  - Первый и второй провал: [MINUTES] минут сервер идёт в конец очереди, потом получает второй шанс.
 *  - Третий провал за [STRIKE_WINDOW_HOURS] часов: карантин на [LONG_HOURS] часов. Такой сервер остаётся в самом конце очереди
 *    и не возвращается первым, даже когда остальные не нашлись ([releaseAll] снимает только короткий карантин).
 *  - Провал при мёртвой сети не считается: сервер тут ни при чём ([add] с networkAlive = false ничего не делает).
 *  - Успешное подключение прощает все провалы ([forgive]).
 *  - Хранится в файле ([attach]): перезапуск службы или процесса карантин не сбрасывает.
 */
object Quarantine {
    const val MINUTES = 10L
    const val LONG_HOURS = 24L
    const val STRIKES_FOR_LONG = 3
    const val STRIKE_WINDOW_HOURS = 24L

    private val lock = Any()
    private val until = HashMap<String, Long>()
    private val long = HashSet<String>()
    private val strikes = HashMap<String, MutableList<Long>>()
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

    fun add(identity: String, now: Long = System.currentTimeMillis(), networkAlive: Boolean = true) {
        if (identity.isEmpty() || !networkAlive || identity.contains('\t') || identity.contains('\n')) return
        synchronized(lock) {
            val list = strikes.getOrPut(identity) { ArrayList() }
            list.removeAll { now - it > STRIKE_WINDOW_HOURS * 3_600_000L }
            list.add(now)
            if (list.size >= STRIKES_FOR_LONG) {
                until[identity] = now + LONG_HOURS * 3_600_000L
                long.add(identity)
            } else {
                until[identity] = now + MINUTES * 60_000L
                long.remove(identity)
            }
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

    /** Сервер снова подтвердил работу: провалы забываются. */
    fun forgive(identity: String) {
        synchronized(lock) {
            val had = strikes.remove(identity) != null
            val q = until.remove(identity) != null
            long.remove(identity)
            if (had || q) save()
        }
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

    internal fun resetForTest() = synchronized(lock) { until.clear(); long.clear(); strikes.clear(); file = null }

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

/** Как часто проверять туннель: спокойный и долго стабильный сервер проверяем реже, чтобы не будить радиомодуль. */
object HealthPace {
    // кратно общей сетке 30 с: фоновые таймеры просыпаются вместе, и радиомодуль не будится по отдельности
    const val GRID_MS = 30_000L
    const val BASE_MS = 30_000L
    const val CALM_MS = 60_000L
    const val LONG_CALM_MS = 90_000L
    const val SCREEN_OFF_MS = 120_000L

    /** Экран выключен, мобильная сеть, связи нет: повтор раз в минуту, а не раз в 30 с (по журналу 15–18.11 больше 2000 строк HEALTH, почти все — неудачи). */
    const val SCREEN_OFF_MOBILE_FAIL_MS = 60_000L

    /** [calmStreak] — сколько проверок подряд без неудач; [failures] — текущие неудачи. */
    const val HOT_MS = 180_000L

    /** [hot] — телефон греется: пока всё спокойно, проверяем туннель реже. */
    const val LOW_BATTERY_MS = 90_000L
    const val LOW_BATTERY_PCT = 20

    /** Заряд низкий и телефон не на зарядке (или включена экономия): пока спокойно, проверяем не чаще, чем раз в 90 с. */
    fun lowBattery(percent: Int, charging: Boolean, powerSave: Boolean): Boolean = !charging && (powerSave || percent in 0 until LOW_BATTERY_PCT)

    fun nextDelay(screenOn: Boolean, failures: Int, calmStreak: Int, hot: Boolean = false, lowBattery: Boolean = false, mobile: Boolean = false): Long {
        val d = when {
            failures > 0 && !screenOn && mobile -> SCREEN_OFF_MOBILE_FAIL_MS
            failures > 0 -> BASE_MS
            hot -> HOT_MS
            !screenOn -> SCREEN_OFF_MS
            calmStreak >= 24 -> LONG_CALM_MS
            calmStreak >= 8 -> CALM_MS
            else -> BASE_MS
        }
        return if (lowBattery && failures == 0) maxOf(d, LOW_BATTERY_MS) else d
    }
}

/**
 * Тяжёлые фоновые задачи (полная перепроверка, аудит источников) по умолчанию идут на зарядке.
 * Чтобы они не голодали, если телефон давно не заряжали, их разрешают и без зарядки: при хорошем заряде, не на мобильной сети,
 * и только если давно не запускались. Ручной запуск разрешён всегда.
 */
object HeavyGate {
    const val STARVE_HOURS = 24L
    const val MIN_BATTERY = 50

    /** [quiet] — тихие часы (ручные или по часам активности): без зарядки не будим радиомодуль, исключение «давно не делали» не действует. */
    fun allow(manual: Boolean, charging: Boolean, batteryPct: Int, metered: Boolean, hoursSinceLast: Long, quiet: Boolean = false, hot: Boolean = false): Boolean = when {
        manual -> true
        hot -> false // телефон греется: тяжёлое откладываем даже на зарядке
        charging -> true
        quiet -> false
        hoursSinceLast >= STARVE_HOURS && batteryPct >= MIN_BATTERY && !metered -> true
        else -> false
    }
}
