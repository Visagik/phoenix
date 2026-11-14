package com.phoenix.app

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime

/** Все данные приложения лежат во внутренней папке (filesDir). Наружу уходит только экспорт. */
class Storage(context: Context, val mode: Mode = Mode.read(context.applicationContext.filesDir)) {
    private val root: File = context.applicationContext.filesDir

    /** Папка данных текущего режима (у каждого режима свои источники, статистика и файлы). */
    val base: File = mode.dir(root)

    val today: String get() = LocalDate.now().toString()

    val stateFile = File(base, "sources_state.txt")
    val blacklistFile = File(base, "blacklist.txt")
    val geoCacheFile = File(base, "geoip_cache.tsv")
    val stableFile = File(base, "stable_configs.tsv")
    val logFile = File(base, "download.log")
    /** Предложения аудита, которые ещё не применены (url, родитель, вид, процент). */
    val proposalsFile = File(base, "audit_proposals.tsv")
    /** Источники, которые пользователь сам вернул из чёрного списка (аудит их не предлагает). */
    val keepFile = File(base, "audit_keep.txt")
    /** Свои ссылки пользователя в пул источников. */
    val userSourcesFile = File(base, "my_sources.txt")
    /** Результаты проверки сервисов через конфиги и выбор проверяемых сервисов. */
    val checksFile = File(base, "service_checks.tsv")
    val exitFile = File(base, "exit_country.tsv")
    /** Цели (приложения и сайты) с их запасными серверами. */
    val targetsFile = File(base, "targets.tsv")
    val missFile = File(base, "target_miss.tsv")
    val statsFile = File(base, "usage_stats.tsv")
    val vetoFile = File(base, "service_veto.tsv")
    val sourceScheduleFile = File(base, "source_schedule.tsv")
    val sourceCacheDir = File(base, "source_cache")
    val sourceValidatorsFile = File(base, "source_validators.tsv")
    /** Опыт приложения: какие конфиги в какой сети работали (по режиму отдельно). */
    val historyFile = File(base, "config_history.tsv")
    val sourceCadenceFile = File(base, "source_cadence.tsv")
    /** Копии файлов перед автоматическими изменениями: их можно вернуть в течение суток. */
    val undoDir = File(base, "undo")
    val parallelFile = File(base, "parallel_limits.tsv")
    /** Последний конфиг, к которому подключались в этом режиме (ссылка и название построчно). */
    val lastLinkFile = File(base, "last_link.txt")
    /** Какие конфиги пришли из какого источника (ключи проверок), чтобы считать долю рабочих по каждой ссылке. */
    val sourceMembersFile = File(base, "source_members.tsv")

    /** Приоритеты ссылок-источников (низкий / обычный / высокий). */
    val sourcePriorityFile = File(base, "source_priority.tsv")

    /** Приоритеты источников, поставленные приложением само (ручные сильнее). */
    val sourceAutoPriorityFile = File(base, "source_priority_auto.tsv")

    /** Приоритет конфига по ключу проверки (CheckStore.keyOf): самый высокий среди ссылок, где он встречается. */
    fun selectedServiceIds(): Set<String> = checkSelection().selected().map { it.id }.toSet()

    fun priorityView(): (String) -> Int {
        val prios = AutoPriority.effective(
            SourcePriorities(sourcePriorityFile).load(),
            SourcePriorities(sourceAutoPriorityFile).load(),
        )
        if (prios.isEmpty() || !sourceMembersFile.exists()) return { 0 }
        val members = try {
            SourceStats.decode(sourceMembersFile.readText())
        } catch (_: Exception) {
            return { 0 }
        }
        val per = SourcePriority.perConfig(members, prios)
        return { key -> per[key] ?: 0 }
    }
    /** Накопленные «голоса» определения типа блокировок (лежит в корне — общий для режимов). */
    val voteFile = File(root, "detect_vote.txt")
    val checkSelectionFile = File(base, "check_services.txt")
    /** Свои сервисы для проверки через конфиг (код, название, адрес). */
    val customServicesFile = File(base, "custom_services.tsv")

    /** Встроенные сервисы, убранные пользователем из проверки. */
    val hiddenServicesFile = File(base, "hidden_services.txt")

    fun hiddenServices() = HiddenServices(hiddenServicesFile)

    fun customServices() = CustomServices(customServicesFile)

    /** Выбор сервисов для проверки: встроенные плюс свои. */
    fun checkSelection() = CheckSelection(checkSelectionFile, { customServices().load() }, { hiddenServices().load() })
    /** Адрес общего списка источников для режима чёрных списков (задаёт пользователь, может быть пустым). */
    val listUrlFile = File(base, "list_url.txt")
    /** Последний удачно скачанный общий список источников — запасной, если GitHub недоступен. */
    val sourcesCacheFile = File(base, "sources_list_cache.txt")

    /**
     * Адрес общего списка источников. Можно задать свой для любого режима; у белых списков пустое значение
     * означает встроенный список, у чёрных — «общего списка нет».
     */
    fun sourcesListUrl(): String {
        val custom = if (listUrlFile.exists()) listUrlFile.readText().trim() else ""
        return when (mode) {
            Mode.WHITELIST -> custom.ifBlank { Collector.SOURCES_LIST_URL }
            Mode.BLACKLIST -> custom
        }
    }

    /** Есть ли у режима откуда собирать: у белых списков всегда, у чёрных — только если задан список или свои ссылки. */
    fun hasSources(): Boolean = DefaultSources.forMode(mode).isNotEmpty() || when (mode) {
        Mode.WHITELIST -> true
        Mode.BLACKLIST -> sourcesListUrl().isNotBlank() || (userSourcesFile.exists() && userSourcesFile.readText().isNotBlank())
    }

    /** Последнее подключение в этом режиме; конфиги другого режима сюда не попадают. */
    fun lastConnection(): Pair<String, String>? = try {
        if (!lastLinkFile.exists()) null
        else lastLinkFile.readLines().let { l -> l.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { it to l.getOrElse(1) { "" }.trim() } }
    } catch (_: Exception) {
        null
    }

    fun saveLastConnection(link: String, title: String) {
        try {
            lastLinkFile.parentFile?.mkdirs()
            lastLinkFile.writeTextAtomic(link.replace('\n', ' ').trim() + "\n" + title.replace('\n', ' ').trim() + "\n")
        } catch (_: Exception) {
        }
    }

    fun history(): ConfigHistory = ConfigHistory(historyFile)

    fun setSourcesListUrl(url: String) {
        listUrlFile.writeTextAtomic(url.trim())
    }

    fun dateDir(date: String = today): File = File(base, date).also { it.mkdirs() }

    /** Свежий файл конфигов (из самой новой папки, где он есть). */
    fun latestEnriched(): File? = latest(ENRICHED_NAME)

    /** Последний итоговый (без меток) файл конфигов — с него Collector переносит рабочие конфиги в новый сбор. */
    fun latestClean(): File? = latest(CLEAN_NAME)

    /** Когда каждый конфиг последний раз приходил из источника (для срока переноса, см. [CarryOver]). */
    val carrySeenFile = File(base, "carry_seen.tsv")

    /** Есть ли уже итоговый файл конфигов за сегодня (значит, сегодняшний сбор выполнен). */
    fun hasEnrichedToday(): Boolean = File(File(base, today), ENRICHED_NAME).exists()

    /** Свежий отчёт — отдельный файл report.txt, не смешанный с конфигами. */
    fun latestReport(): File? = latest(REPORT_NAME)

    private fun latest(fileName: String): File? =
        base.listFiles { f -> f.isDirectory && DATE_DIR.matches(f.name) }
            ?.sortedByDescending { it.name }
            ?.map { File(it, fileName) }
            ?.firstOrNull { it.exists() }

    fun log(message: String) {
        try {
            // Storage(context) создаётся заново в разных местах, поэтому замок общий на файл, а не на объект: иначе обрезка одного писателя затирала бы строки другого
            synchronized(lockFor(logFile)) {
                LogTrim.trimIfNeeded(logFile) // копится до 5 МБ, потом уходят самые старые строки
                logFile.appendText("${OffsetDateTime.now()} ${LogMask.apply(message)}\n")
            }
        } catch (_: Exception) {
        }
    }

    fun cleanupOld(keepDays: Long = 30) {
        val limit = LocalDate.now().minusDays(keepDays)
        base.listFiles { f -> f.isDirectory && DATE_DIR.matches(f.name) }?.forEach { dir ->
            val d = try {
                LocalDate.parse(dir.name)
            } catch (_: Exception) {
                return@forEach
            }
            if (d.isBefore(limit)) dir.deleteRecursively()
        }
    }

    companion object {
        private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** Один замок на путь файла. */
        internal fun lockFor(f: File): Any = fileLocks.getOrPut(f.absolutePath) { Any() }

        /** Режимы, для которых ночной сбор имеет смысл (у чёрных списков без источников он пропускается). */
        fun enabledModes(context: Context): List<Mode> = Mode.entries.filter { Storage(context, it).hasSources() }

        /** Сегодняшний сбор выполнен для каждого включённого режима. */
        fun allDoneToday(context: Context): Boolean = enabledModes(context).all { Storage(context, it).hasEnrichedToday() }

        const val ENRICHED_NAME = "all-vpn-configs-enriched.txt"
        const val CLEAN_NAME = "all-vpn-configs.txt"
        const val REPORT_NAME = "report.txt"
        private val DATE_DIR = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
