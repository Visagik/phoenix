package com.phoenix.app

/** Оболочки Android, которые по-разному ограничивают работу в фоне. Определение не зависит от Android — проверяется тестами на JVM. */
enum class FirmwareKind(val title: String) {
    EMUI("EMUI"),
    HARMONY("HarmonyOS"),
    MAGICOS("MagicOS"),
    MIUI("MIUI"),
    HYPEROS("HyperOS"),
    COLOROS("ColorOS"),
    REALME("Realme UI"),
    FUNTOUCH("Funtouch OS / OriginOS"),
    ONEUI("One UI"),
    OXYGEN("OxygenOS"),
    FLYME("Flyme"),
    ASUS("ZenUI"),
    STOCK("Android"),
}

data class FirmwareInfo(val kind: FirmwareKind, val version: String, val manufacturer: String, val sdk: Int) {
    /** «EMUI 12.0.0 · Huawei · Android 12» */
    fun text(): String {
        val androidName = androidNames[sdk]?.let { "Android $it" } ?: "API $sdk"
        val os = if (version.isBlank()) kind.title else "${kind.title} $version"
        val maker = manufacturer.replaceFirstChar { it.uppercase() }
        return listOf(os, maker.takeIf { it.isNotBlank() }, androidName).filterNotNull().joinToString(" · ")
    }

    companion object {
        private val androidNames = mapOf(29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16", 37 to "17")
    }
}

object FirmwareDetect {
    /**
     * @param props значение системного свойства по имени (пустая строка, если нет)
     */
    fun detect(manufacturer: String, brand: String, display: String, sdk: Int, props: (String) -> String): FirmwareInfo {
        val maker = manufacturer.trim().lowercase()
        val br = brand.trim().lowercase()
        fun p(k: String) = props(k).trim()

        val emui = p("ro.build.version.emui")
        val magic = p("ro.build.version.magic").ifBlank { p("ro.build.magic_version") }
        val harmony = p("hw_sc.build.platform.version")
        val hyper = p("ro.mi.os.version.name")
        val miui = p("ro.miui.ui.version.name")
        val realme = p("ro.build.version.realmeui")
        val color = p("ro.build.version.opporom")
        val vivo = p("ro.vivo.os.version")
        val oneui = p("ro.build.version.oneui")
        val oxygen = p("ro.oxygen.version").ifBlank { p("ro.build.ota.versionname") }

        fun info(kind: FirmwareKind, version: String) = FirmwareInfo(kind, version, manufacturer.trim(), sdk)

        return when {
            magic.isNotBlank() -> info(FirmwareKind.MAGICOS, magic.substringAfterLast('_').ifBlank { magic })
            emui.isNotBlank() -> info(FirmwareKind.EMUI, emui.substringAfter("EmotionUI_").substringAfter("EMUI_").trim())
            harmony.isNotBlank() -> info(FirmwareKind.HARMONY, harmony)
            hyper.isNotBlank() -> info(FirmwareKind.HYPEROS, hyper.removePrefix("OS"))
            miui.isNotBlank() -> info(FirmwareKind.MIUI, miui.removePrefix("V"))
            realme.isNotBlank() -> info(FirmwareKind.REALME, realme)
            color.isNotBlank() -> info(FirmwareKind.COLOROS, color.removePrefix("V"))
            vivo.isNotBlank() -> info(FirmwareKind.FUNTOUCH, vivo)
            oneui.isNotBlank() -> info(FirmwareKind.ONEUI, oneuiVersion(oneui))
            display.contains("flyme", ignoreCase = true) || maker == "meizu" -> info(FirmwareKind.FLYME, "")
            oxygen.isNotBlank() && maker == "oneplus" -> info(FirmwareKind.OXYGEN, "")
            else -> when (maker) {
                "huawei" -> info(FirmwareKind.EMUI, "")
                "honor" -> info(FirmwareKind.MAGICOS, "")
                "xiaomi", "redmi", "poco" -> info(FirmwareKind.MIUI, "")
                "oppo" -> info(FirmwareKind.COLOROS, "")
                "realme" -> info(FirmwareKind.REALME, "")
                "vivo", "iqoo" -> info(FirmwareKind.FUNTOUCH, "")
                "samsung" -> info(FirmwareKind.ONEUI, "")
                "oneplus" -> info(FirmwareKind.OXYGEN, "")
                "asus" -> info(FirmwareKind.ASUS, "")
                else -> if (br == "huawei" || br == "honor") info(if (br == "honor") FirmwareKind.MAGICOS else FirmwareKind.EMUI, "") else info(FirmwareKind.STOCK, "")
            }
        }
    }

    /** One UI кодирует версию числом: 50100 → 5.1.0, 40101 → 4.1.1. */
    fun oneuiVersion(raw: String): String {
        val n = raw.toIntOrNull() ?: return raw
        if (n < 10000) return raw
        return "${n / 10000}.${n / 100 % 100}.${n % 100}"
    }
}

/** Адрес системного экрана настроек (пакет + класс). Классы у разных версий прошивок отличаются, поэтому пробуются по очереди. */
data class ScreenSpec(val pkg: String, val cls: String)

/** Кнопка в руководстве: подпись и экраны, которые нужно попробовать открыть по очереди. */
data class FirmwareAction(val label: String, val screens: List<ScreenSpec>)

data class FirmwareGuide(val title: String, val steps: List<String>, val actions: List<FirmwareAction>)

object FirmwareGuides {
    private val common = listOf(
        "Оптимизация батареи: Приложения → Особый доступ → Оптимизация батареи → Phoenix → «Не оптимизировать» (кнопка ниже делает это в один шаг).",
        "Закрепите Phoenix в списке недавних (замок или потянуть карточку вниз): так система реже его выгружает.",
        "Уведомление Phoenix оставьте включённым: это уведомление держит VPN живым.",
    )

    fun forKind(kind: FirmwareKind): FirmwareGuide = when (kind) {
        FirmwareKind.EMUI, FirmwareKind.HARMONY, FirmwareKind.MAGICOS -> FirmwareGuide(
            title = "Huawei / Honor (${kind.title})",
            steps = listOf(
                "Запуск приложений: Настройки → Батарея → Запуск приложений → Phoenix → выключить «Автоматическое управление» → включить три переключателя: «Автозапуск», «Косвенный запуск» и «Работа в фоновом режиме». Без этого EMUI замораживает приложение при выключенном экране — сбор по расписанию и переключение серверов не сработают.",
                "Связь в спящем режиме: Настройки → Батарея → Больше настроек батареи → «Сохранять подключение к сети в спящем режиме» → «Всегда». Иначе при выключенном экране VPN может терять соединение.",
                "Экономия трафика: Настройки → Мобильная сеть → Использование данных → Экономия трафика → если она включена, добавьте Phoenix в «Неограниченный доступ к данным».",
                "У телефонов Huawei нет сервисов Google: Phoenix их не использует, проверки идут напрямую по сети, ничего дополнительно ставить не нужно.",
            ) + common,
            actions = listOf(
                FirmwareAction(
                    "Запуск приложений (Huawei / Honor)",
                    listOf(
                        ScreenSpec("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        ScreenSpec("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                        ScreenSpec("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                        ScreenSpec("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        ScreenSpec("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                    ),
                ),
                FirmwareAction(
                    "Управление батареей (Huawei / Honor)",
                    listOf(
                        ScreenSpec("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
                        ScreenSpec("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"),
                    ),
                ),
            ),
        )
        FirmwareKind.MIUI, FirmwareKind.HYPEROS -> FirmwareGuide(
            title = "Xiaomi (${kind.title})",
            steps = listOf(
                "Автозапуск: Настройки → Приложения → Разрешения → Автозапуск → включить Phoenix.",
                "Контроль активности: Настройки → Приложения → Phoenix → Контроль активности → «Нет ограничений».",
                "Экономия батареи Phoenix — «Нет ограничений»; закрепите приложение в недавних (замок).",
            ) + common,
            actions = listOf(
                FirmwareAction("Автозапуск (Xiaomi)", listOf(ScreenSpec("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))),
                FirmwareAction("Экономия батареи (Xiaomi)", listOf(ScreenSpec("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))),
            ),
        )
        FirmwareKind.COLOROS, FirmwareKind.REALME -> FirmwareGuide(
            title = "Oppo / Realme (${kind.title})",
            steps = listOf(
                "Автозапуск: Настройки → Приложения → Автозапуск → включить Phoenix.",
                "Батарея: Настройки → Батарея → Phoenix → «Разрешить работу в фоне» и «Разрешить автозапуск».",
            ) + common,
            actions = listOf(
                FirmwareAction(
                    "Автозапуск (Oppo / Realme)",
                    listOf(
                        ScreenSpec("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        ScreenSpec("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        ScreenSpec("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                        ScreenSpec("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                    ),
                ),
            ),
        )
        FirmwareKind.FUNTOUCH -> FirmwareGuide(
            title = "Vivo / iQOO (${kind.title})",
            steps = listOf(
                "Автозапуск: i Менеджер → Менеджер приложений → Автозапуск → включить Phoenix.",
                "Батарея: Настройки → Батарея → Потребление в фоне → Phoenix → «Разрешить».",
            ) + common,
            actions = listOf(
                FirmwareAction(
                    "Фоновый запуск (Vivo)",
                    listOf(
                        ScreenSpec("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        ScreenSpec("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                        ScreenSpec("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    ),
                ),
            ),
        )
        FirmwareKind.ONEUI -> FirmwareGuide(
            title = "Samsung (${kind.title})",
            steps = listOf(
                "Настройки → Обслуживание устройства → Батарея → Ограничения фоновой работы → выключите «Усыплять неиспользуемые приложения» или добавьте Phoenix в «Никогда не усыплять».",
                "Настройки → Приложения → Phoenix → Батарея → «Без ограничений».",
            ) + common,
            actions = listOf(
                FirmwareAction(
                    "Батарея (Samsung)",
                    listOf(
                        ScreenSpec("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                        ScreenSpec("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                        ScreenSpec("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    ),
                ),
            ),
        )
        FirmwareKind.OXYGEN -> FirmwareGuide(
            title = "OnePlus (${kind.title})",
            steps = listOf(
                "Настройки → Батарея → Оптимизация батареи → Phoenix → «Не оптимизировать».",
                "Настройки → Приложения → Phoenix → Использование батареи → «Разрешить фоновую активность» и «Автозапуск».",
            ) + common,
            actions = listOf(
                FirmwareAction("Автозапуск (OnePlus)", listOf(ScreenSpec("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))),
            ),
        )
        FirmwareKind.FLYME -> FirmwareGuide(
            title = "Meizu (${kind.title})",
            steps = listOf("Безопасность → Разрешения → Фоновое управление → Phoenix → «Разрешить работу в фоне».") + common,
            actions = listOf(FirmwareAction("Фоновое управление (Meizu)", listOf(ScreenSpec("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")))),
        )
        FirmwareKind.ASUS -> FirmwareGuide(
            title = "Asus (${kind.title})",
            steps = listOf("Менеджер мобильных → Автозапуск → включить Phoenix.") + common,
            actions = listOf(FirmwareAction("Автозапуск (Asus)", listOf(ScreenSpec("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")))),
        )
        FirmwareKind.STOCK -> FirmwareGuide(
            title = "Чистый Android",
            steps = common,
            actions = emptyList(),
        )
    }
}
