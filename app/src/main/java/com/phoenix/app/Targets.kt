package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

enum class TargetKind { APP, SITE }

/** Что делать, если цель не работает на текущем сервере. */
enum class Policy(val title: String) {
    DEFAULT("как везде"),
    /** Приложение само выбирает между А и Б (см. Target.resolve). */
    AUTO("выбирать само"),
    /** Вариант А: только эта цель идёт через свой сервер, остальное — как раньше. */
    PROXY("только она — через свой сервер"),
    /** Вариант Б: при открытии приложения переключить весь VPN на его сервер. */
    SWITCH("переключить весь VPN"),
}

/** Приложение или сайт, для которого держатся запасные серверы. */
data class Target(
    val id: String,
    val kind: TargetKind,
    /** Имя пакета (APP) или домен (SITE) */
    val value: String,
    val label: String,
    /** Адрес, по которому проверяется, что цель работает через сервер */
    val checkUrl: String,
    val policy: Policy = Policy.DEFAULT,
    /** Проверенные рабочие серверы по порядку предпочтения (ссылки целиком) */
    val servers: List<String> = emptyList(),
    val verifiedAt: String = "",
) {
    /** Для цели нет подходящего сервера, но сайт открывается напрямую: пускаем её мимо VPN (см. [DIRECT]). */
    val isDirect: Boolean get() = servers.firstOrNull() == DIRECT

    fun effective(global: Policy): Policy =
        if (policy != Policy.DEFAULT) policy else if (global == Policy.DEFAULT) Policy.AUTO else global

    /**
     * Итоговый вариант с учётом возможностей телефона и ядра:
     * сайты — всегда А (по домену); приложение — А, если ядро умеет узнавать приложение по соединению,
     * иначе Б (если выдан доступ к данным об использовании), иначе всё равно А.
     */
    fun resolve(global: Policy, finderOk: Boolean, usageOk: Boolean): Policy {
        val eff = effective(global)
        if (eff != Policy.AUTO) return eff
        return when {
            kind == TargetKind.SITE -> Policy.PROXY
            finderOk -> Policy.PROXY
            usageOk -> Policy.SWITCH
            else -> Policy.PROXY
        }
    }

    companion object {
        /** Особое «значение сервера»: идти напрямую (outbound «direct»), а не через прокси. */
        const val DIRECT = "phoenix:direct"

        fun app(pkg: String, label: String, checkUrl: String) =
            Target("APP:$pkg", TargetKind.APP, pkg, label.ifBlank { pkg }, checkUrl)

        /** @return null, если это не адрес сайта */
        fun site(input: String): Target? {
            val host = Domains.host(input) ?: return null
            val url = if (input.trim().startsWith("http", ignoreCase = true)) input.trim() else "https://$host/"
            return Target("SITE:$host", TargetKind.SITE, host, host, url)
        }
    }
}

/** Известные приложения: по какому адресу проверять, что они работают. */
object KnownApps {
    private val probes: Map<String, String> = mapOf(
        "org.telegram.messenger" to "https://web.telegram.org/",
        "org.telegram.messenger.web" to "https://web.telegram.org/",
        "org.thunderdog.challegram" to "https://web.telegram.org/",
        "com.google.android.youtube" to "https://www.youtube.com/generate_204",
        "app.revanced.android.youtube" to "https://www.youtube.com/generate_204",
        "com.instagram.android" to "https://www.instagram.com/",
        "com.whatsapp" to "https://web.whatsapp.com/",
        "com.discord" to "https://discord.com/",
        "com.valvesoftware.android.steam.community" to "https://store.steampowered.com/",
        "com.anthropic.claude" to "https://claude.ai/",
        "com.openai.chatgpt" to "https://chatgpt.com/",
        "ai.qwenlm.chat.android" to "https://chat.qwen.ai/",
        "com.deepseek.chat" to "https://chat.deepseek.com/",
        "ai.x.grok" to "https://grok.com/",
        "com.google.android.apps.labs.language.tailwind" to "https://notebooklm.google.com/",
        "org.smarttube.stable" to "https://www.youtube.com/generate_204",
        "com.spotify.music" to "https://open.spotify.com/",
        "com.netflix.mediaclient" to "https://www.netflix.com/",
        "com.zhiliaoapp.musically" to "https://www.tiktok.com/",
        "com.twitter.android" to "https://x.com/",
        "com.facebook.katana" to "https://www.facebook.com/",
        "org.thoughtcrime.securesms" to "https://signal.org/",
        "com.viber.voip" to "https://www.viber.com/",
        "com.reddit.frontpage" to "https://www.reddit.com/",
        "tv.twitch.android.app" to "https://www.twitch.tv/",
        "com.google.android.apps.bard" to "https://gemini.google.com/",
        // Gemini на телефоне открывается из приложения Google (журнал 07.11: «открыто com.google.android.googlequicksearchbox, серверов цели 0»)
        "com.google.android.googlequicksearchbox" to "https://gemini.google.com/",
        "com.google.android.apps.youtube.music" to "https://music.youtube.com/",
        "com.microsoft.copilot" to "https://copilot.microsoft.com/",
        "ai.perplexity.app.android" to "https://www.perplexity.ai/",
        "com.pinterest" to "https://www.pinterest.com/",
        "com.linkedin.android" to "https://www.linkedin.com/",
        "com.snapchat.android" to "https://www.snapchat.com/",
        "com.instagram.barcelona" to "https://www.threads.net/",
        "com.soundcloud.android" to "https://soundcloud.com/",
        "deezer.android.app" to "https://www.deezer.com/",
        "com.github.android" to "https://github.com/",
        "com.medium.reader" to "https://medium.com/",
        "us.zoom.videomeetings" to "https://zoom.us/",
        "com.google.android.apps.meetings" to "https://meet.google.com/",
        "com.skype.raider" to "https://www.skype.com/",
        "org.wikipedia" to "https://www.wikipedia.org/",
        "com.duolingo" to "https://www.duolingo.com/",
        "com.openai.sora" to "https://sora.com/",
        "com.google.android.apps.photos" to "https://photos.google.com/",
        "com.google.android.apps.docs" to "https://docs.google.com/",
        "com.google.android.gm" to "https://mail.google.com/",
        "com.microsoft.teams" to "https://teams.microsoft.com/",
        "com.dropbox.android" to "https://www.dropbox.com/",
        "com.brave.browser" to "https://search.brave.com/",
        "org.mozilla.firefox" to "https://www.mozilla.org/",
    )

    /**
     * Если адрес приложения неизвестен, проверяется просто работа сервера (ответ 204 без тела). Это слабее, чем открыть сам сервис,
     * но запасные серверы для приложения всё равно подбираются; точнее адрес можно указать вручную.
     */
    const val DEFAULT_URL = "https://www.gstatic.com/generate_204"

    fun checkUrlFor(pkg: String): String? = probes[pkg]

    /** Адрес проверки для любого приложения: известный или общий. */
    fun urlOrDefault(pkg: String): String = probes[pkg] ?: DEFAULT_URL

    fun isKnown(pkg: String): Boolean = pkg in probes
}

/** Файл целей: id, вид, значение, имя, адрес проверки, политика, дата проверки, серверы… (через табуляцию). */
class TargetStore private constructor(private val file: File) {
    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, TargetStore>()

        /** Один объект на файл: тогда @Synchronized защищает файл от одновременной записи из интерфейса и фоновых задач. */
        operator fun invoke(file: File): TargetStore = cache.getOrPut(file.absolutePath) { TargetStore(file) }
    }

    fun load(): List<Target> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 7) return@mapNotNull null
            val kind = TargetKind.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null
            val policy = Policy.entries.firstOrNull { it.name == p[5] } ?: Policy.DEFAULT
            // старые записи с общей проверкой (gstatic) получают адрес самого сервиса, если он теперь известен
            val url = if (kind == TargetKind.APP && p[4] == KnownApps.DEFAULT_URL) KnownApps.checkUrlFor(p[2]) ?: p[4] else p[4]
            Target(p[0], kind, p[2], p[3], url, policy, p.drop(7).filter { it.isNotBlank() }, p[6])
        }
    }

    @Synchronized
    fun save(targets: List<Target>) {
        file.parentFile?.mkdirs()
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        file.writeTextAtomic(targets.joinToString("\n") { t ->
            (listOf(t.id, t.kind.name, t.value, clean(t.label), t.checkUrl, t.policy.name, t.verifiedAt) +
                t.servers.map { clean(it) }).joinToString("\t")
        } + if (targets.isEmpty()) "" else "\n")
    }

    /** @return null — добавлено, иначе текст ошибки */
    @Synchronized
    fun add(t: Target): String? {
        val all = load()
        if (all.any { it.id == t.id }) return "Уже в списке"
        if (!t.checkUrl.startsWith("http")) return "Нужен адрес проверки вида https://…"
        save(all + t)
        return null
    }

    @Synchronized
    fun remove(id: String) = save(load().filter { it.id != id })

    /** Меняет только политику (серверы, найденные фоном, не затираются устаревшим состоянием экрана). */
    @Synchronized
    fun setPolicy(id: String, policy: Policy) =
        save(load().map { if (it.id == id) it.copy(policy = policy) else it })

    /** Меняет только адрес проверки — так же, как setPolicy. `update(t.copy(checkUrl = …))` c экрана несёт старые
     * `servers`/`verifiedAt` того объекта Target, который UI открыл когда-то раньше: если список серверов обновился
     * фоном между открытием экрана цели и правкой адреса, такая правка стирала свежие серверы устаревшими (пустыми). */
    @Synchronized
    fun setUrl(id: String, url: String) =
        save(load().map { if (it.id == id) it.copy(checkUrl = url) else it })

    @Synchronized
    fun update(t: Target) = save(load().map { if (it.id == t.id) t else it })

    /** Обновляет только серверы и дату проверки — настройки пользователя (политика, адрес) не затираются. */
    @Synchronized
    fun updateServers(id: String, servers: List<String>, date: String) =
        save(load().map { if (it.id == id) it.copy(servers = servers, verifiedAt = date) else it })
}

data class Candidate(
    val link: String,
    val identity: String,
    val days: Int,
    val mbps: Double,
    val latencyMs: Long,
    /** Сколько из выбранных сервисов открывается через этот конфиг (его «возможности») */
    val caps: Int = 0,
    /** Надёжность конфига в текущей сети по накопленному опыту (0.5 — данных нет). */
    val rel: Double = 0.5,
    /** В этой сети конфиг уже несколько раз подводил: его ставят в самый конец. */
    val bad: Boolean = false,
    /** Недавно (последние 30 минут) на нём оборвалась сессия: в конец очереди, потом второй шанс. */
    val quarantined: Boolean = false,
    /** В этой сети держится стабильно: не меньше 8 удач и надёжность ≥ 90%. Лёгкий бонус в рейтинге. */
    val steady: Boolean = false,
    /** Свежая проверка показала, что конфиг отвечает (не «неизвестно»): таких пробуют первыми. */
    val alive: Boolean = false,
    /** По названию конфиг предназначен для другого режима (например, «[Белые списки]» в чёрных): пробуется после остальных. */
    val mismatch: Boolean = false,
    /** Конфиг взят из списка другого режима (белых при поиске в чёрных): так его можно подмешать в очередь выше конца. */
    val foreign: Boolean = false,
    /** Когда-то не ответил (возможно, в другой сети): остаётся в игре, но после тех, о ком такого не известно. */
    val deadMark: Boolean = false,
    /** Приоритет ссылки-источника, из которой пришёл конфиг (−1 низкий, 0 обычный, 1 высокий). */
    val prio: Int = 0,
    /** Сервер для узкой цели (Госуслуги, торренты) или с российским выходом в чёрных списках: для общего VPN он хуже обычных. */
    val unfit: Boolean = false,
    /** Страна выхода сервера (по названию/адресу), пусто — неизвестна. */
    val cc: String = "",
    /** Название прямо говорит, что сервер для текущего режима («[WL] …» в белых списках): для режима он надёжнее случайных. */
    val modeHint: Boolean = false,
    /** Тип конфига (Reality, Trojan…) в этой сети: 0 — выручает, 1 — мало данных, 2 — подводит. См. [ProtocolLearning]. */
    val protoBucket: Int = 1,
) {
    /** Хост сервера: конфиги с одним хостом и разными протоколами — один и тот же сервер. */
    val host: String get() = ConfigIdentity.host(link)
}

object Ranking {
    /** Сначала стабильные (3+ дня), потом по скорости, потом по задержке; не измеренное — в конце. */
    fun rank(list: List<Candidate>): List<Candidate> = list.sortedWith(
        compareBy<Candidate> { it.bad }
            .thenBy { it.quarantined }
            .thenBy { it.mismatch }
            .thenBy { it.unfit }
            .thenByDescending { it.alive }
            .thenBy { it.deadMark }
            .thenByDescending { it.modeHint }
            .thenBy { it.protoBucket }
            .thenByDescending { it.prio }
            .thenByDescending { it.steady }
            .thenByDescending { it.days >= 3 }
            .thenByDescending { it.caps }
            .thenByDescending { Math.round(it.rel * 4) }
            .thenByDescending { it.mbps }
            .thenBy { if (it.latencyMs <= 0) Long.MAX_VALUE else it.latencyMs }
    )

    /**
     * Оставляет по одному конфигу на сервер (порядок рейтинга): запасной с тем же сервером, но другим протоколом,
     * упадёт вместе с основным. Конфиги без хоста не склеиваются.
     */
    fun distinctServers(ranked: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        return ranked.filter { it.host.isEmpty() || seen.add(it.host) }
    }

    /** Сколько дней результат «не отвечает» считается свежим: потом конфигу дают второй шанс. */
    const val DEAD_TTL_DAYS = 2L

    /**
     * Кандидаты из списка конфигов. Мёртвые по свежей проверке отбрасываются; если проверка старая
     * или адрес сервера с тех пор сменился (то же имя, новый сервер) — конфиг снова в игре.
     */
    fun candidates(
        rows: List<ConfigRow>,
        checks: Map<String, CheckResult>,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        history: ((String) -> HistStat?)? = null,
        mode: Mode? = null,
        /**
         * Не выбрасывать «мёртвых»: конфиг мог не отвечать в другой сети (белые списки), а здесь жить.
         * Для поиска запасных нужны все конфиги; отметка «не отвечал» только ставит их в очередь позже.
         */
        includeDead: Boolean = false,
        /** Приоритет конфига по ключу проверки (см. Storage.priorityView). */
        priority: ((String) -> Int)? = null,
        /** Сервисы, выбранные пользователем: «рабочесть» считается только по ним (null — по всем). */
        svcIds: Set<String>? = null,
    ): List<Candidate> = withProtocolLearning(candidatesRaw(rows, checks, today, history, mode, includeDead, priority, svcIds), history)

    /** Добавляет к кандидатам оценку типа конфига по опыту в этой сети. */
    private fun withProtocolLearning(list: List<Candidate>, history: ((String) -> HistStat?)?): List<Candidate> {
        if (history == null || list.isEmpty()) return list
        val rates = ProtocolLearning.rates(list.map { it.link to history(it.identity) })
        if (rates.values.none { it.total >= ProtocolLearning.MIN_SAMPLES }) return list
        return list.map { c -> val b = ProtocolLearning.bucket(ProtocolLearning.kindOf(c.link), rates); if (b == 1) c else c.copy(protoBucket = b) }
    }

    private fun candidatesRaw(
        rows: List<ConfigRow>,
        checks: Map<String, CheckResult>,
        today: java.time.LocalDate,
        history: ((String) -> HistStat?)?,
        mode: Mode?,
        includeDead: Boolean,
        priority: ((String) -> Int)?,
        svcIds: Set<String>?,
    ): List<Candidate> =
        rows.mapNotNull { row ->
            val check = checks[CheckStore.keyOf(row.raw)]
            var deadMark = false
            if (check != null && check.dead && !check.staleFor(row.raw)) {
                val checked = try {
                    java.time.LocalDate.parse(check.date)
                } catch (_: Exception) {
                    null
                }
                val fresh = checked == null || !checked.isBefore(today.minusDays(DEAD_TTL_DAYS))
                if (fresh) {
                    if (!includeDead) return@mapNotNull null
                    deadMark = true
                }
            }
            val identity = ConfigIdentity.key(row.raw)
            val stat = history?.invoke(identity)
            Candidate(
                link = row.raw,
                identity = identity,
                days = row.days,
                mbps = check?.mbps ?: -1.0,
                latencyMs = check?.baselineMs ?: 0,
                caps = check?.services?.entries?.count { (svcIds == null || it.key in svcIds) && CheckServices.opened(it.value) } ?: 0,
                rel = stat?.reliability ?: 0.5,
                bad = stat?.bad == true,
                quarantined = Quarantine.active(identity),
                steady = stat != null && stat.ok >= 8 && stat.reliability >= 0.9,
                alive = check != null && !check.dead && !check.staleFor(row.raw) && isFresh(check.date, today),
                mismatch = mode != null && ListHint.of(row.displayName).let { it != null && it != mode },
                deadMark = deadMark,
                prio = priority?.invoke(CheckStore.keyOf(row.raw)) ?: 0,
                // в белых списках российские и «целевые» серверы как раз рабочие; в чёрных YouTube и прочее через них не открыть
                cc = row.cc?.uppercase().orEmpty(),
                modeHint = mode != null && ListHint.of(row.displayName) == mode,
                unfit = mode == Mode.BLACKLIST &&
                    (ListHint.special(row.displayName) || row.cc.equals("RU", true) || ListHint.russian(row.displayName)),
            )
        }

    private fun isFresh(date: String, today: java.time.LocalDate): Boolean = try {
        !java.time.LocalDate.parse(date).isBefore(today.minusDays(1))
    } catch (_: Exception) {
        false
    }

    /** Кого пробовать при падении туннеля: лучшие по рейтингу, кроме уже испробованных и текущего. */
    fun failoverOrder(
        candidates: List<Candidate>,
        tried: Set<String>,
        currentIdentity: String,
        limit: Int = 8,
        currentHost: String = "",
        triedHosts: Set<String> = emptySet(),
    ): List<Candidate> =
        distinctServers(
            rank(candidates).filter {
                it.identity != currentIdentity && it.identity !in tried &&
                    (it.host.isEmpty() || (it.host != currentHost && it.host !in triedHosts))
            }
        ).take(limit)
}

/**
 * Находит для цели до [keep] рабочих серверов. Сначала перепроверяются прежние (чтобы список не прыгал),
 * потом лучшие из остальных. [probe] — одна проверка (конфиг JSON, адрес) → мс или -1.
 */
class TargetVerifier(
    private val probe: (config: String, url: String) -> Long,
    private val parallel: Int = 3,
    private val keep: Int = 3,
    private val maxTests: Int = 12,
    private val reach: (String, Int) -> String? = { h, _ -> h },
    /** Запасная, мягкая проверка: любой HTTP-ответ сайта (даже 403 от защиты) значит, что сервер до него дотянулся. */
    private val soft: (link: String, url: String) -> Boolean = { _, _ -> false },
    /** Страна выхода сервера (для сервисов, закрытых по региону); null — не узнать. */
    private val exit: (link: String) -> String? = { null },
    /** Содержимое страницы через сервер: true — «не поддерживается в вашей стране»; null — не удалось узнать. */
    private val region: (link: String, url: String) -> Boolean? = { _, _ -> null },
    private val veto: (link: String, url: String) -> Boolean = { _, _ -> false },
) {
    /** Сколько серверов проверено при последнем вызове (для журнала). */
    @Volatile var lastTested = 0
        private set

    /** Почему серверы не подошли при последнем вызове: «TCP не открылся», «ядро не получило ответ», «регион закрыт». */
    @Volatile var lastReasons: Map<String, Int> = emptyMap()
        private set
    private val reasons = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun verify(target: Target, candidates: List<Candidate>, maxTests: Int = this.maxTests, skip: (String) -> Boolean = { false }): List<String> {
        val order = ArrayList<Candidate>()
        val seen = HashSet<String>()
        val byIdentity = candidates.associateBy { it.identity }
        for (old in target.servers) {
            if (old == Target.DIRECT || skip(old)) continue
            val id = ConfigIdentity.key(old)
            // адрес мог смениться — берём свежую ссылку с тем же именем, иначе прежнюю
            if (seen.add(id)) order += byIdentity[id] ?: Candidate(old, id, 0, -1.0, 0)
        }
        // порядок задаёт вызывающий (сервисы, страна выхода): заново по рейтингу не сортируем
        for (c in candidates) if (!skip(c.link) && seen.add(c.identity)) order += c

        reasons.clear()
        val ok = ArrayList<String>()
        val okHosts = HashSet<String>()
        var tested = 0
        var i = 0
        while (i < order.size && ok.size < keep && tested < maxTests) {
            // запасные должны быть на разных серверах: тот же хост с другим протоколом падает вместе с первым
            val batch = ArrayList<Candidate>()
            while (i < order.size && batch.size < parallel) {
                val c = order[i++]
                if (c.host.isEmpty() || c.host !in okHosts) batch += c
            }
            if (batch.isEmpty()) break
            val results = coroutineScope {
                batch.map { c -> async(Dispatchers.IO) { c to works(c.link, target.checkUrl) } }.awaitAll()
            }
            tested += batch.size
            for ((c, good) in results) {
                if (good && ok.size < keep && (c.host.isEmpty() || okHosts.add(c.host))) ok += c.link
            }
        }
        lastTested = tested
        lastReasons = HashMap(reasons)
        return ok
    }

    private fun fail(reason: String): Boolean {
        reasons.merge(reason, 1, Int::plus)
        return false
    }

    private fun works(link: String, url: String): Boolean {
        val parsed = LinkParser.parse(link)
        var hosts: Map<String, String> = emptyMap()
        if (parsed?.host != null && parsed.port != null) {
            val ip = (if (parsed.protocol == "hysteria2") ServerHosts.resolveOnly(parsed.host) else reach(parsed.host, parsed.port)) ?: return fail("TCP не открылся")
            hosts = ServerHosts.hostsFor(parsed.host, ip)
        }
        val config = try {
            XrayConfig.probeConfig(link, hosts)
        } catch (_: UnsupportedLinkException) {
            return fail("не поддерживается")
        }
        val opened = probe(config, url) >= 0 || soft(link, url)
        if (!opened) return fail("сайт не открылся через сервер")
        // сайт мог ответить заглушкой «недоступно в регионе» с кодом 200: проверяем страну выхода
        if (GeoGate.applies(url) && GeoGate.blocks(url, exit(link))) return fail("страна выхода закрыта для сайта")
        if (veto(link, url)) return fail("вы отметили, что сайт здесь не открывается")
        if (GeoGate.applies(url) && region(link, url) == true) return fail("страница сайта: недоступно в стране выхода")
        return true
    }
}

/** Маршрут «эта цель — через свой сервер» внутри общего VPN-соединения. */
data class AppRoute(
    val tag: String,
    val link: String,
    /** UID приложений (ядро сопоставляет соединения с приложением по UID) */
    val uids: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    /** Идти напрямую, мимо прокси ([link] пуст). */
    val direct: Boolean = false,
)

object PlanBuilder {
    /**
     * Какие цели нужно пустить через собственные серверы при основном конфиге [mainLink].
     * Цель, чей проверенный сервер совпадает с основным (по личности), отдельного маршрута не получает.
     */
    fun routes(
        mainLink: String,
        targets: List<Target>,
        global: Policy,
        finderOk: Boolean = true,
        usageOk: Boolean = false,
        uidOf: (String) -> String?,
    ): List<AppRoute> {
        val mainId = ConfigIdentity.key(mainLink)
        val out = ArrayList<AppRoute>()
        // сервисы Google с проверкой региона ведём через один сервер (общий для них, если такой есть) вместе с общими узлами Google
        val googleTargets = targets.filter { GoogleAi.isGoogleAi(it) && it.resolve(global, finderOk, usageOk) == Policy.PROXY && it.servers.isNotEmpty() && !it.isDirect }
        val googleShared = GoogleAi.commonServer(googleTargets.map { it.servers })
        var familyAdded = false
        for (t in targets) {
            if (t.resolve(global, finderOk, usageOk) != Policy.PROXY) continue
            if (t.servers.isEmpty()) continue
            if (t.servers.any { ConfigIdentity.key(it) == mainId }) continue
            val isG = t in googleTargets
            val link = if (t.isDirect) "" else if (isG && googleShared != null) googleShared else t.servers.first()
            val tag = "t${out.size}"
            when (t.kind) {
                TargetKind.APP -> {
                    val uid = uidOf(t.value) ?: continue // приложение не установлено
                    val family = if (isG && !familyAdded) GoogleAi.familyRules else emptyList()
                    if (isG) familyAdded = true
                    out += AppRoute(tag, link, uids = listOf(uid), domains = AppDomains.rules(t.value) + family, direct = t.isDirect)
                }
                TargetKind.SITE -> {
                    val family = if (isG && !familyAdded) GoogleAi.familyRules else emptyList()
                    if (isG) familyAdded = true
                    out += AppRoute(tag, link, domains = listOf(Domains.rule(t.value)) + family, direct = t.isDirect)
                }
            }
        }
        return out
    }

    /**
     * Вариант Б: приложение [foreground] открыто, и для него настроено «переключить весь VPN».
     * @return цель, чей сервер нужно сделать основным, или null
     */
    fun switchFor(
        foreground: String,
        targets: List<Target>,
        global: Policy,
        mainLink: String,
        lastSwitchAt: Map<String, Long>,
        now: Long,
        cooldownMs: Long = 2 * 60 * 1000L,
        finderOk: Boolean = true,
        usageOk: Boolean = true,
    ): Target? {
        val mainId = ConfigIdentity.key(mainLink)
        val t = targets.firstOrNull { it.kind == TargetKind.APP && it.value == foreground } ?: return null
        if (t.resolve(global, finderOk, usageOk) != Policy.SWITCH) return null
        if (t.servers.isEmpty() || t.isDirect || t.servers.any { ConfigIdentity.key(it) == mainId }) return null
        val last = lastSwitchAt[t.id]
        if (last != null && now - last < cooldownMs) return null
        return t
    }
}

/**
 * В каком порядке пробовать серверы, когда нужно быстро получить рабочее соединение (смена режима, первое подключение):
 * сначала конфиги из приоритетных ссылок, потом тот, что был в прошлый раз, потом остальные по рейтингу.
 * Один сервер (хост) — один раз: «брат» с тем же хостом упадёт вместе с первым.
 */
object QuickOrder {
    /** Надёжность в текущей сети, с которой сервер считается проверенным: две удачи и ни одной неудачи дают 0.75. */
    const val PROVEN_REL = 0.7

    fun order(ranked: List<Candidate>, lastLink: String?, limit: Int, lastGood: String? = null): List<String> {
        val usable = ranked.filter { !it.bad }
        // сервер, который последним реально работал в этой сети и режиме, пробуем первым (если он ещё в списке и не помечен плохим)
        val goodLink = lastGood?.let { g -> usable.firstOrNull { ConfigIdentity.key(it.link) == ConfigIdentity.key(g) }?.link }
        // сервер, названный для этого режима, надёжнее «высокого приоритета» без такой метки (в логе: Болгария #95 пробовалась раньше [WL] Сервер 34)
        // сервер, который именно в этой сети уже не раз срабатывал, надёжнее всех догадок по названию (в логе быстрые варианты в белых списках не работали по 45 с)
        val proven = usable.filter { it.rel >= PROVEN_REL }.sortedByDescending { it.rel }.map { it.link }
        val hinted = usable.filter { it.modeHint }.map { it.link }
        val high = usable.filter { it.prio > 0 && !it.modeHint }.map { it.link }
        val rest = usable.filter { it.prio <= 0 && !it.modeHint }.map { it.link }
        val seenHosts = HashSet<String>()
        val seenLinks = HashSet<String>()
        val out = ArrayList<String>()
        for (link in listOfNotNull(goodLink) + proven + hinted + high + listOfNotNull(lastLink) + rest) {
            if (out.size >= limit) break
            if (!seenLinks.add(link)) continue
            val host = ConfigIdentity.host(link)
            if (host.isNotEmpty() && !seenHosts.add(host)) continue
            out += link
        }
        return out
    }
}

/** Какие HTTP-коды считать «сайт досягаем». Ошибки сервера (5xx) — нет: прокси мог вернуть свою ошибку. */
object Soft {
    fun accepts(code: Int, body: String = ""): Boolean = when {
        code !in 200..499 -> false
        // 403 бывает и от защиты сайта (вопрос-проверка Cloudflare: сервер дотянулся), и от блокировки по стране выхода
        // (российский выход для Claude): засчитываем только явную проверку-челлендж
        code == 403 || code == 451 -> challenge.containsMatchIn(body) && !region.containsMatchIn(body)
        code in 400..499 -> !region.containsMatchIn(body)
        else -> true
    }

    private val challenge = Regex("(?i)just a moment|cf-chl|challenge-platform|enable javascript and cookies")

    private val region = Regex("(?i)not available in your (country|region)|unavailable in your (country|region)|app unavailable|isn't available in your|access denied.{0,40}(country|region)|не доступ\\S* в вашем регионе")

    /** В теле ответа сказано, что сервис закрыт для региона выхода: это не «открылся». */
    fun regionBlocked(body: String): Boolean = region.containsMatchIn(body)
}

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
