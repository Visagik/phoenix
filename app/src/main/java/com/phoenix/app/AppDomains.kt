package com.phoenix.app

/**
 * Сайты известных приложений: ссылки этих приложений в браузере идут через тот же подготовленный сервер, что и само приложение.
 * Общие домены (google.com, microsoft.com целиком) сюда не входят: только то, что принадлежит самому сервису.
 */
object AppDomains {
    private val telegram = listOf("t.me", "telegram.org", "telegram.me", "telegra.ph", "telesco.pe", "tdesktop.com", "telegram-cdn.org")
    private val youtube = listOf("youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "youtube-nocookie.com", "youtubei.googleapis.com")
    private val discord = listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media")
    private val chatgpt = listOf("chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com")
    private val twitter = listOf("x.com", "twitter.com", "twimg.com", "t.co")

    private val map: Map<String, List<String>> = mapOf(
        "org.telegram.messenger" to telegram,
        "org.telegram.messenger.web" to telegram,
        "org.thunderdog.challegram" to telegram,
        "com.google.android.youtube" to youtube,
        "app.revanced.android.youtube" to youtube,
        "org.smarttube.stable" to youtube,
        "com.google.android.apps.youtube.music" to youtube,
        "com.instagram.android" to listOf("instagram.com", "cdninstagram.com", "ig.me"),
        "com.instagram.barcelona" to listOf("threads.net", "threads.com"),
        "com.whatsapp" to listOf("whatsapp.com", "whatsapp.net", "wa.me"),
        "com.discord" to discord,
        "com.valvesoftware.android.steam.community" to listOf("steampowered.com", "steamcommunity.com", "steamstatic.com"),
        "com.anthropic.claude" to listOf("claude.ai", "anthropic.com", "claude.com"),
        "com.openai.chatgpt" to chatgpt,
        "com.openai.sora" to listOf("sora.com"),
        "ai.qwenlm.chat.android" to listOf("qwen.ai", "qwenlm.ai"),
        "com.deepseek.chat" to listOf("deepseek.com"),
        "ai.x.grok" to listOf("grok.com", "x.ai"),
        "com.google.android.apps.labs.language.tailwind" to listOf("notebooklm.google.com"),
        "com.google.android.apps.bard" to listOf("gemini.google.com", "bard.google.com"),
        "com.spotify.music" to listOf("spotify.com", "scdn.co", "spotifycdn.com"),
        "com.netflix.mediaclient" to listOf("netflix.com", "nflxvideo.net", "nflximg.net"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "musical.ly"),
        "com.twitter.android" to twitter,
        "com.facebook.katana" to listOf("facebook.com", "fb.com", "fbcdn.net", "messenger.com"),
        "org.thoughtcrime.securesms" to listOf("signal.org"),
        "com.viber.voip" to listOf("viber.com"),
        "com.reddit.frontpage" to listOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"),
        "tv.twitch.android.app" to listOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
        "com.microsoft.copilot" to listOf("copilot.microsoft.com"),
        "ai.perplexity.app.android" to listOf("perplexity.ai"),
        "com.pinterest" to listOf("pinterest.com", "pinimg.com"),
        "com.linkedin.android" to listOf("linkedin.com", "licdn.com"),
        "com.snapchat.android" to listOf("snapchat.com"),
        "com.soundcloud.android" to listOf("soundcloud.com", "sndcdn.com"),
        "deezer.android.app" to listOf("deezer.com"),
        "com.github.android" to listOf("github.com", "githubusercontent.com", "githubassets.com"),
        "com.medium.reader" to listOf("medium.com"),
        "us.zoom.videomeetings" to listOf("zoom.us"),
        "com.google.android.apps.meetings" to listOf("meet.google.com"),
        "com.skype.raider" to listOf("skype.com"),
        "org.wikipedia" to listOf("wikipedia.org", "wikimedia.org"),
        "com.duolingo" to listOf("duolingo.com"),
        "com.google.android.apps.photos" to listOf("photos.google.com"),
        "com.google.android.apps.docs" to listOf("docs.google.com"),
        "com.google.android.gm" to listOf("mail.google.com"),
        "com.microsoft.teams" to listOf("teams.microsoft.com"),
        "com.dropbox.android" to listOf("dropbox.com"),
    )

    /** Домены приложения (пусто, если приложение неизвестно). */
    fun hosts(pkg: String): List<String> = map[pkg].orEmpty()

    /** Правила Xray «domain:…» для приложения. */
    fun rules(pkg: String): List<String> = hosts(pkg).map { Domains.rule(it) }

    /** Домен совпадает с [base] или является его поддоменом. */
    fun covers(base: String, host: String): Boolean = host == base || host.endsWith(".$base")

    /** Какое из известных приложений владеет этим сайтом (первое по списку), либо null. */
    fun packageFor(host: String): String? =
        map.entries.firstOrNull { (_, hs) -> hs.any { covers(it, host) } }?.key
}


/**
 * Сервисы Google с проверкой региона (Gemini, NotebookLM). Для них вход в аккаунт и служебные запросы идут на общие узлы Google
 * (accounts.google.com, googleapis.com, Play Services), а не на сам сервис. Если сервис выходит через один сервер, а эти узлы через другой,
 * Google видит две страны для одного аккаунта и отвечает «не поддерживается» или не грузит чаты (журнал 07.11: страница Gemini через Финляндию
 * открывается, а пользователю не работает). Поэтому такие цели ведутся через ОДИН сервер, и общие узлы Google идут туда же.
 */
object GoogleAi {
    private val packages = setOf("com.google.android.apps.bard", "com.google.android.apps.labs.language.tailwind", "com.google.android.googlequicksearchbox")
    private val sites = listOf("gemini.google.com", "bard.google.com", "notebooklm.google.com", "notebooklm.google", "notebook.google", "aistudio.google.com")

    /** Общие узлы Google, через которые сервис проверяет аккаунт и страну. */
    val familyRules: List<String> = listOf(
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "ggpht.com", "gvt1.com", "notebooklm.google", "notebook.google",
    ).map { Domains.rule(it) }

    const val GEO_ORACLE_URL = "https://notebooklm.google.com/"
    private val oracleHosts = listOf("gemini.google.com", "bard.google.com", "aistudio.google.com", "ai.google.dev")

    /** Для этих адресов страна выхода по самой странице не видна: решает проверка NotebookLM через тот же сервер. */
    fun needsOracle(url: String): Boolean {
        val h = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
        return oracleHosts.any { AppDomains.covers(it, h) }
    }

    fun isGoogleAi(t: Target): Boolean = when (t.kind) {
        TargetKind.APP -> t.value in packages
        TargetKind.SITE -> sites.any { AppDomains.covers(it, t.value.lowercase()) }
    }

    /** Общий для всех [servers] списков сервер (по личности конфига) в порядке первого списка; null — общего нет. */
    fun commonServer(lists: List<List<String>>): String? {
        if (lists.isEmpty()) return null
        val others = lists.drop(1).map { l -> l.map { ConfigIdentity.key(it) }.toSet() }
        return lists.first().firstOrNull { link -> others.all { ConfigIdentity.key(link) in it } }
    }
}
