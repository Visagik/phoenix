package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

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
    /** Просьба остановиться: следующую пачку серверов не проверяем, найденное возвращаем. */
    private val stop: () -> Boolean = { false },
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
        while (i < order.size && ok.size < keep && tested < maxTests && !stop()) {
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
        if (GeoGate.applies(url) && RegionVote.decide(3) { region(link, url) } == true) return fail("страница сайта: недоступно в стране выхода")
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
        directBlocked: Set<String> = emptySet(),
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
            // «мимо VPN» записано при подборе в другой сети, а здесь цель напрямую не открывается (DirectLearn): своего маршрута нет, идёт общим путём
            if (t.isDirect && t.id in directBlocked) continue
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
