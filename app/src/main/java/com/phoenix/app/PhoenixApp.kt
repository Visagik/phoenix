package com.phoenix.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        // язык, выбранный в приложении (на Android 13+ язык выбирает система, свой выбор не нужен)
        if (android.os.Build.VERSION.SDK_INT < 33) {
            try { Tr.override = AppSettings(this).language.ifBlank { null } } catch (_: Throwable) { }
        }
        // причина падения (если оно случится) сохранится и попадёт в журнал при следующем запуске
        try {
            CrashLog.install(filesDir)
        } catch (_: Throwable) {
        }
        // Каждый запуск процесса (в том числе от будильника или после перезагрузки) проверяет, что всё запланировано,
        // и догоняет пропущенный сбор, если он не прошёл.
        // остатки оборванных записей (временные файлы) не нужны
        // почему система закрыла прошлый процесс (падение, ANR, лимит памяти Android 17) — в журнал, не на главном потоке
        NetHooks.offline = { NetInfo.kind(this) == NetInfo.Kind.NONE }
        NetHooks.mobile = { NetInfo.kind(this) == NetInfo.Kind.MOBILE }
        NetHooks.carryDays = { AppSettings(this).carryDays }
        NetHooks.label = { NetInfo.label(this) }
        NetHooks.level = { st, net -> GovernedLimits.get(st).level(net) }
        // диск и WorkManager — не в основном потоке (первый кадр не ждёт): прошлое падение в журнал, обрывки записей, планировщик
        Thread {
            try { PoolPolicyStore.load(this) } catch (_: Throwable) { }
            try { LogTrim.configure(AppSettings(this).logSize) } catch (_: Throwable) { }
            try { Quarantine.attach(java.io.File(filesDir, "quarantine.txt")) } catch (_: Throwable) { }
            try { CrashLog.take(filesDir)?.let { t -> val st = Storage(this); CrashLog.logLines(t).forEach { st.log(it) } } } catch (_: Throwable) { }
            try { AtomicWrite.cleanup(filesDir) } catch (_: Throwable) { }
            ExitLog.report(this)
            try { GeoRulesFetch.refresh(this, Storage(this)) } catch (_: Throwable) { }
            try { Scheduler.ensureScheduled(this) } catch (_: Throwable) { }
        }.start()
        // виджет на рабочем столе повторяет состояние VPN
        appScope.launch {
            // заметка (note) тоже видна в виджете; частые смены склеиваются: не чаще раза в секунду
            VpnState.status.map { Pair(Triple(it.phase, it.title, it.error), it.note) }.distinctUntilChanged().conflate().collect {
                PhoenixWidgetProvider.refresh(this@PhoenixApp)
                delay(1_000)
            }
        }
    }
}
