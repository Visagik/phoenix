package com.phoenix.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Чем занят Phoenix прямо сейчас, кроме сборки и перепроверки (у тех свои состояния): для строки на главном экране. */
object LiveActivity {
    /** Подбор серверов для целей («Цели»). Пусто — не идёт. */
    val targets = MutableStateFlow("")

    /** Подробности подбора для целей: сколько найдено, сколько без сервера. null — не идёт. */
    val targetsDetail = MutableStateFlow<TargetsDetail?>(null)

    /** Вид показа на главной ([ActivityStyle]); задаётся в «Настройках». */
    val style = MutableStateFlow(ActivityStyle.DEFAULT)
}
