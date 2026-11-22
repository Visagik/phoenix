package com.phoenix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Тип блокировок определяется до работы, зависящей от режима (перепроверка, в том числе ручная), и при запуске приложения:
 * определение раньше работало только при включённом VPN, и свежая установка весь первый сеанс жила в режиме «по умолчанию»
 * (журнал 22.11: ручная перепроверка белых серверов в чёрной сети). Замер прямой, VPN не нужен.
 */
object ModeFirst {
    private val lock = Any()

    /** true — режим определён (или определять не нужно) и актуален. */
    suspend fun ensure(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val st = AppSettings(app)
        val held = ModeHold.active(st.manualModeAt, st.manualModeNet, System.currentTimeMillis(), NetInfo.label(app))
        if (!ModeGate.needsDetect(st.autoMode, ModeGate.knownNet, NetInfo.place(app), held)) return@withContext true
        if (NetInfo.kind(app) == NetInfo.Kind.NONE) return@withContext false
        synchronized(lock) {
            // пока ждали очереди, другой вызов мог уже определить
            if (!ModeGate.needsDetect(st.autoMode, ModeGate.knownNet, NetInfo.place(app), held)) return@synchronized true
            repeat(2) { i ->
                val outcome = ModeDetector.run(app, 1)
                if (ModeDecider.modeOf(outcome.type) != null) return@synchronized true
                if (i == 0) Thread.sleep(4_000)
            }
            false
        }
    }
}
