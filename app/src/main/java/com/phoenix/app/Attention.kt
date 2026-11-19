package com.phoenix.app

/** Что показать в «Ещё → Требует внимания» и красной точкой на вкладке: отозванные разрешения и новая версия. */
object Attention {
    enum class Item { BACKGROUND, NOTIFICATIONS, ALARMS, UPDATE }

    fun items(env: Onboarding.Env, updateAvailable: Boolean): List<Item> = buildList {
        if (!env.batteryOk) add(Item.BACKGROUND)
        if (env.notificationsRelevant && !env.notificationsGranted) add(Item.NOTIFICATIONS)
        if (env.exactRelevant && !env.exactOk) add(Item.ALARMS)
        if (updateAvailable) add(Item.UPDATE)
    }

    /** Сколько пунктов относятся к странице «Работа в фоне» (для подписи «нужно N»). */
    fun backgroundCount(items: List<Item>): Int = items.count { it != Item.UPDATE }
}
