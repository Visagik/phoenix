package com.phoenix.app

import android.content.Context

/** Проверка лимита фоновых проверок по мобильной сети. Вне мобильной сети лимит не действует. */
object BackgroundBudgetGate {
    fun exhausted(context: Context): Boolean {
        return try {
            if (NetInfo.kind(context) != NetInfo.Kind.MOBILE) return false
            val limit = AppSettings(context).mobileBudgetMb
            val used = EnergyMeter.of(java.io.File(context.filesDir, "energy.tsv")).mobileBytes(java.time.LocalDate.now().toString())
            val over = BackgroundBudget.exhausted(used, limit)
            if (over) Storage(context).log("Лимит фоновых проверок по мобильной сети ($limit МБ в сутки) исчерпан: пропускаю")
            over
        } catch (_: Throwable) {
            false
        }
    }
}
