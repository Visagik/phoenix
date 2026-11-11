package com.phoenix.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Проверка списка обхода напрямую (приложение всегда мимо собственного VPN). Логика счётчиков — в [DirectWatch]. */
object DirectProbe {
    private fun reachable(url: String): Boolean = reachableOnce(url) || reachableOnce(url) // один таймаут (медленная сеть) не считается отказом

    private fun reachableOnce(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4_000
        c.readTimeout = 4_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Phoenix)")
        try { c.responseCode in 200..499 } finally { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    /**
     * Один проход: проверяет сервисы каталога напрямую и учит, что в этой сети открывается без VPN ([DirectLearn]).
     * [onlyDue] — только то, что нужно проверить: неизвестное и недоверенное всегда, доверенное (3 удачи подряд) — раз в 12 часов.
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run(context: Context, storage: Storage, onlyDue: Boolean = false): Int {
        // перепроверка и смена сети могут позвать проход одновременно: второй не должен затереть итог первого
        if (!running.compareAndSet(false, true)) {
            storage.log("BYPASS: проверка уже выполняется — второй запуск пропускаю")
            return 0
        }
        try { return runOnce(context, storage, onlyDue) } finally { running.set(false) }
    }

    private fun runOnce(context: Context, storage: Storage, onlyDue: Boolean): Int {
        if (NetInfo.kind(context) == NetInfo.Kind.NONE) return 0
        val net = NetInfo.label(context)
        val bypass = BypassStore(storage.base, storage.mode, net)
        val now = System.currentTimeMillis()
        val old = DirectLearn.load(bypass.learnFile)
        val all = DirectCatalog.ENTRIES.filter { if (it.russian) bypass.presetEnabled else bypass.openEnabled }
        if (all.isEmpty()) return 0
        val entries = if (onlyDue) all.filter { DirectLearn.due(DirectLearn.entry(old, net, it.id), now) } else all
        if (entries.isEmpty()) {
            storage.log("BYPASS: в сети $net все ${all.size} сервисов уже проверены и доверенные — проверки не нужны (проактивно раз в 12 часов)")
            return 0
        }
        val pool = Executors.newFixedThreadPool(6)
        val ok = try {
            pool.invokeAll(entries.map { e -> Callable { e.id to reachable(e.url) } }, 30, TimeUnit.SECONDS)
                .mapNotNull { f -> try { if (f.isCancelled) null else f.get() } catch (_: Exception) { null } }.toMap()
        } finally {
            pool.shutdownNow()
        }
        // нет вообще ничего (нет сети) — не делаем выводов
        if (ok.values.none { it }) {
            storage.log("BYPASS: напрямую не открылось ничего (${ok.size} адресов) — похоже, сети нет, список обхода не меняю")
            return 0
        }
        var next: Map<String, LearnEntry> = old
        var changes = 0
        for (e in entries) {
            val res = ok[e.id] ?: continue
            val before = DirectLearn.entry(next, net, e.id)
            next = DirectLearn.record(next, net, e.id, res, now)
            val after = DirectLearn.entry(next, net, e.id)
            if (DirectLearn.blocked(before) != DirectLearn.blocked(after)) {
                changes++
                storage.log(
                    if (DirectLearn.blocked(after)) "BYPASS: «${e.id}» (${e.url}) в сети $net напрямую не открывается ${after!!.failRun} раза подряд — пускаю через VPN"
                    else "BYPASS: «${e.id}» (${e.url}) в сети $net открывается напрямую — пускаю в обход",
                )
            } else if (res && !DirectLearn.trusted(before, now) && DirectLearn.trusted(after, now)) {
                storage.log("BYPASS: «${e.id}» в сети $net открылся напрямую ${after!!.okRun} раза подряд — доверяю без проверок, перепроверю проактивно раз в 12 часов")
            } else if (!res && DirectLearn.trusted(before, now)) {
                storage.log("BYPASS: «${e.id}» в сети $net перестал открываться напрямую — доверие снято, проверяю чаще")
            }
        }
        DirectLearn.save(bypass.learnFile, next)
        val blocked = DirectLearn.blockedIds(next, net)
        val trusted = all.count { DirectLearn.trusted(DirectLearn.entry(next, net, it.id), now) }
        storage.log(
            "BYPASS: сеть $net, проверено напрямую ${ok.size} из ${all.size}, открылось ${ok.values.count { it }}, доверенных $trusted, через VPN сейчас: " +
                (if (blocked.isEmpty()) "нет" else blocked.joinToString()) + if (changes > 0) " (применяю к работающему VPN)" else "",
        )
        if (changes > 0) PhoenixVpnService.applyBypass(context)
        return changes
    }
}
