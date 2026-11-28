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

    /** Настоящие серверы цели без служебной метки «мимо VPN» ([DIRECT]); метку нигде не фильтруем вручную. */
    val realServers: List<String> get() = servers.filter { it != DIRECT }

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

    /** Дописывает цели, которых ещё нет в файле (по id), не трогая существующие: безопасно, если файл тем временем обновил фон. */
    @Synchronized
    fun addMissing(wanted: List<Target>) {
        val now = load()
        val have = now.mapTo(HashSet()) { it.id }
        val add = wanted.filter { it.id !in have }
        if (add.isNotEmpty()) save(now + add)
    }

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

/**
 * Какие цели затрагивает пометка «сервис на этом сервере не открывается»: только те, чей адрес проверки — тот же сайт.
 * Раньше сервер вычёркивался у всех целей, где он есть: «Не работает» в NotebookLM убирало общий сервер у Telegram и Discord (находка аудита C2/T7).
 */
object TargetSite {
    fun sameSite(t: Target, url: String): Boolean {
        val a = ServiceVeto.hostOf(t.checkUrl)
        val b = ServiceVeto.hostOf(url)
        return a.isNotEmpty() && b.isNotEmpty() && ServiceVeto.sameSite(a, b)
    }

    fun affected(targets: List<Target>, link: String, url: String): List<Target> {
        val id = ConfigIdentity.key(link)
        return targets.filter { t -> sameSite(t, url) && t.servers.any { it != Target.DIRECT && ConfigIdentity.key(it) == id } }
    }
}

/**
 * Список целей (какие приложения и сайты вести через VPN) у пользователя один, а файлы целей лежат по режимам («Чёрные списки» / «Белые списки»).
 * Раньше после смены режима работал список этого режима: в белых оказывалось 8 целей вместо 12, остальные приходилось ставить заново
 * (журнал 22.11). Теперь недостающие цели переносятся во все режимы (серверы у каждого режима свои и у новой цели пусты — подберутся сами).
 */
object TargetSync {
    /** Цели из [others], которых нет в [current] (по id): копии без серверов и даты проверки. */
    fun missing(current: List<Target>, others: List<List<Target>>): List<Target> {
        val have = current.mapTo(HashSet()) { it.id }
        val out = ArrayList<Target>()
        for (list in others) for (t in list) if (have.add(t.id)) out.add(t.copy(servers = emptyList(), verifiedAt = ""))
        return out
    }

    /** Новый список для режима: прежний + недостающие. null — менять нечего. */
    fun merged(current: List<Target>, others: List<List<Target>>): List<Target>? {
        val add = missing(current, others)
        return if (add.isEmpty()) null else current + add
    }
}
