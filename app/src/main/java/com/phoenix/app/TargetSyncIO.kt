package com.phoenix.app

import android.content.Context

/** Выравнивает списки целей по режимам (см. [TargetSync]). Дёшево: файлы маленькие, повторные вызовы чаще раза в 30 с пропускаются. */
object TargetSyncIO {
    @Volatile private var last = 0L

    fun merge(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - last < 30_000L) return
        last = now
        try {
            val stores = Mode.entries.map { m -> m to TargetStore(Storage(context.applicationContext, m).targetsFile) }
            val lists = stores.associate { (m, s) -> m to s.load() }
            for ((m, store) in stores) {
                val others = lists.filterKeys { it != m }.values.toList()
                val merged = TargetSync.merged(lists.getValue(m), others) ?: continue
                store.addMissing(merged)
            }
        } catch (_: Exception) {
        }
    }
}
