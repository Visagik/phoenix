package com.phoenix.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoenixApp : Application() {
    // собственная область на всё время жизни процесса (вместо GlobalScope, на который ругается компилятор)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // словари переводов интерфейса лежат в assets/i18n; нет словаря — показывается русский исходник
        Tr.loader = { lang ->
            try { assets.open("i18n/$lang.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Exception) { null }
        }
        // причина падения (если оно случится) сохранится и попадёт в журнал при следующем запуске
        try {
            CrashLog.install(filesDir)
            CrashLog.take(filesDir)?.let { t -> val st = Storage(this); CrashLog.logLines(t).forEach { st.log(it) } }
        } catch (_: Throwable) {
        }
        // Каждый запуск процесса (в том числе от будильника или после перезагрузки) проверяет, что всё запланировано,
        // и догоняет пропущенный сбор, если он не прошёл.
        // остатки оборванных записей (временные файлы) не нужны
        try { AtomicWrite.cleanup(filesDir) } catch (_: Throwable) {}
        // почему система закрыла прошлый процесс (падение, ANR, лимит памяти Android 17) — в журнал, не на главном потоке
        Thread { ExitLog.report(this); try { GeoRulesFetch.refresh(this, Storage(this)) } catch (_: Throwable) { } }.start()
        NetHooks.offline = { NetInfo.kind(this) == NetInfo.Kind.NONE }
        NetHooks.mobile = { NetInfo.kind(this) == NetInfo.Kind.MOBILE }
        NetHooks.label = { NetInfo.label(this) }
        NetHooks.level = { st, net -> GovernedLimits.get(st).level(net) }
        Scheduler.ensureScheduled(this)
        // виджет на рабочем столе повторяет состояние VPN
        appScope.launch {
            VpnState.status.collect { PhoenixWidgetProvider.refresh(this@PhoenixApp) }
        }
    }
}
