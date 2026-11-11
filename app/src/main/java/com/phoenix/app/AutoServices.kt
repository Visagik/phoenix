package com.phoenix.app

import android.content.Context
import java.time.LocalDate

/**
 * Сами проверяем выбранные сервисы у рабочих конфигов: результат записывается к конфигу (в «Проверено» и в рейтинг).
 * Берутся живые по свежей проверке, у которых нет результата хотя бы по одному выбранному сервису, лучшие по рейтингу первыми.
 */
object AutoServices {
    const val LIMIT = 30

    /** Сколько рабочих конфигов за прогон получают настоящую страну выхода (каждая — один короткий запуск ядра). */
    const val EXIT_PER_RUN = 12

    /** Настоящая страна выхода: сначала из кэша (не старше 3 дней), иначе Cloudflare trace через сам сервер. */
    fun exitOf(context: Context, storage: Storage, link: String): String? {
        val store = ExitStore(storage.exitFile)
        val key = CheckStore.keyOf(link)
        store.fresh(key, LocalDate.now())?.let { return it }
        val cc = SpeedRunner.exitCountry(context, link) ?: return null
        store.put(key, cc, LocalDate.now().toString())
        return cc
    }

    /** @return сколько конфигов проверено */
    suspend fun run(context: Context, storage: Storage, limit: Int = LIMIT, all: Boolean = false): Int {
        if (!XrayCore.available || (!all && !AppSettings(context).autoServiceCheck)) return 0
        val services = storage.checkSelection().selected()
        if (services.isEmpty()) return 0
        val rows = ConfigRepository.load(storage)
        val store = CheckStore(storage.checksFile)
        val ranked = Ranking.rank(
            Ranking.candidates(rows, store.load(), history = storage.historyView(context), mode = storage.mode, priority = storage.priorityView(), svcIds = storage.selectedServiceIds())
        )
        val todo = ServicePlan.pending(ranked, store.load(), services, limit, all)
        if (todo.isEmpty()) return 0
        val names = rows.associate { CheckStore.keyOf(it.raw) to it.displayName }
        val started = System.currentTimeMillis()
        val checker = ServiceChecker(
            probe = { cfg, url -> ProbeShare.measure(cfg, url) { XrayCore.measureDelay(context, cfg, url) } },
            parallel = CheckLimits.parallel(context),
            reach = { h, p -> ServerHosts.reach(h, p) },
            soft = { link, url -> SpeedRunner.reaches(context, link, url) },
            exit = { link -> exitOf(context, storage, link) },
            region = { link, url -> SpeedRunner.regionBlocked(context, link, url) },
            veto = { link, url -> ServiceVeto(storage.vetoFile).active(link, url) },
        )
        val netBefore = NetInfo.label(context)
        var skippedNet = 0
        val results = checker.check(todo.map { it.link }, services, LocalDate.now().toString()) { key, res ->
            // сеть сменилась посреди проверки: результат относится к другой сети, в базу его не пишем
            if (NetInfo.label(context) == netBefore) store.putAll(mapOf(key to res)) else skippedNet++
        }
        if (skippedNet > 0) storage.log("SVC: сеть сменилась ($netBefore → ${NetInfo.label(context)}) — $skippedNet результатов не сохранено")
        val alive = results.filter { !it.value.dead }
        // настоящая страна выхода у рабочих: по названию её можно только угадать
        var exitDone = 0
        val exitStore = ExitStore(storage.exitFile)
        for (c in todo) {
            if (exitDone >= EXIT_PER_RUN) break
            val key = CheckStore.keyOf(c.link)
            if (results[key]?.dead != false || exitStore.fresh(key, LocalDate.now()) != null) continue
            if (exitOf(context, storage, c.link) != null) exitDone++
        }
        if (exitDone > 0) storage.log("EXIT: настоящая страна выхода определена у $exitDone конфигов")
        storage.log(
            "SVC: сервисы ${services.joinToString(",") { it.id }} проверены у ${results.size} конфигов (рабочих ${alive.size}) " +
                "за ${(System.currentTimeMillis() - started) / 1000} с"
        )
        for ((key, r) in alive.entries.take(10)) {
            storage.log("SVC: [${names[key] ?: key}] ${ServicePlan.summary(r, services)}")
        }
        val broken = services.filter { s -> alive.isNotEmpty() && alive.values.none { CheckServices.opened(it.services[s.id]) } }
        if (broken.isNotEmpty()) {
            storage.log("SVC: ни через один рабочий конфиг не открылось: ${broken.joinToString { it.title }} (возможно, сервис закрыт в этой сети или адрес неверный)")
        }
        return results.size
    }
}
