package com.phoenix.app

/** Цвет уведомления службы: зелёный — всё работает, жёлтый — идёт подключение или поиск сервера, красный — нет связи. */
enum class NotifyTone(val argb: Int, val mark: String) {
    GREEN(0xFF2E7D32.toInt(), "🟢"),
    YELLOW(0xFFF9A825.toInt(), "🟡"),
    RED(0xFFC62828.toInt(), "🔴");

    companion object {
        fun of(connected: Boolean, connecting: Boolean, note: String): NotifyTone {
            val n = note.lowercase()
            return when {
                connecting -> YELLOW
                connected && (n.startsWith("нет связи") || n.contains("заблокирован")) -> RED
                connected && (n.contains("ищу") || n.contains("меняю") || n.contains("проверяю")) -> YELLOW
                connected -> GREEN
                else -> YELLOW
            }
        }
    }
}
