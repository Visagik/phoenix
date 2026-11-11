package com.phoenix.app

/** Прошивка по производителю и экраны автозапуска, куда можно перейти напрямую (если такого экрана нет — открываются настройки приложения). */
object OemGuide {
    enum class Vendor(val title: String) { XIAOMI("Xiaomi / Redmi / Poco"), HUAWEI("Huawei / Honor"), OPPO("Oppo / Realme / OnePlus"), VIVO("Vivo / iQOO"), SAMSUNG("Samsung"), OTHER("Другой телефон") }

    fun vendor(manufacturer: String, brand: String = ""): Vendor {
        val s = "$manufacturer $brand".lowercase()
        return when {
            "xiaomi" in s || "redmi" in s || "poco" in s -> Vendor.XIAOMI
            "huawei" in s || "honor" in s -> Vendor.HUAWEI
            "oppo" in s || "realme" in s || "oneplus" in s -> Vendor.OPPO
            "vivo" in s || "iqoo" in s -> Vendor.VIVO
            "samsung" in s -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    /**
     * Производитель по железу может не совпадать с прошивкой: на Redmi с кастомной прошивкой (Infinity X, LineageOS…) нет ни MIUI, ни её
     * экрана автозапуска. Если телефон Xiaomi, а прошивка не MIUI/HyperOS — подсказки как для чистого Android.
     */
    fun vendorFor(manufacturer: String, brand: String, kind: FirmwareKind): Vendor {
        val v = vendor(manufacturer, brand)
        return if (v == Vendor.XIAOMI && kind != FirmwareKind.MIUI && kind != FirmwareKind.HYPEROS) Vendor.OTHER else v
    }

    /** (пакет, класс) экранов автозапуска, по порядку попыток. */
    fun autostart(v: Vendor): List<Pair<String, String>> = when (v) {
        Vendor.XIAOMI -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
        Vendor.HUAWEI -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        Vendor.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        Vendor.VIVO -> listOf(
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        else -> emptyList()
    }

    /** Что включить вручную на этой прошивке. */
    fun hint(v: Vendor): String = when (v) {
        Vendor.XIAOMI -> "Автозапуск: включить для Phoenix. Экономия энергии: «Без ограничений». В списке недавних задержите карточку и поставьте замок."
        Vendor.HUAWEI -> "Запуск приложений: отключите автоматическое управление для Phoenix и включите автозапуск, косвенный запуск и работу в фоне."
        Vendor.OPPO -> "Автозапуск: включить. Батарея: «Разрешить работу в фоне». Данные: отключить ограничение фоновых данных для Phoenix."
        Vendor.VIVO -> "Автозапуск и «Работа в фоне с высоким потреблением»: разрешить Phoenix."
        Vendor.SAMSUNG -> "Батарея → Ограничения в фоновом режиме: добавьте Phoenix в «Неспящие приложения»."
        Vendor.OTHER -> "Разрешите Phoenix работу в фоне и уберите ограничение батареи."
    }
}
