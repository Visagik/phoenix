package com.phoenix.app

/** Цвет уведомления службы: зелёный — всё работает, жёлтый — идёт подключение или поиск сервера, красный — нет связи. */
/**
 * Уточнение состояния, которое раньше приходилось угадывать по тексту заметки (а текст переводится на язык интерфейса):
 * служба отдаёт его явно. NONE — обычное состояние, смысл несёт фаза.
 */
enum class VpnSub {
    NONE,
    /** Идёт поиск другого сервера (защита держится, туннель не рвётся или меняется). */
    SEARCHING,
    /** У телефона нет сети или у сети нет интернета: сервер ни при чём, подключусь, когда сеть появится. */
    NO_NETWORK,
    /** Связи нет именно через сервер или интернет заблокирован защитой. */
    BROKEN,
}

enum class NotifyTone(val argb: Int, val mark: String) {
    GREEN(0xFF2E7D32.toInt(), "🟢"),
    YELLOW(0xFF9A5B00.toInt(), "🟡"),
    RED(0xFFC62828.toInt(), "🔴");

    companion object {
        /**
         * Нужно ли перерисовать уведомление: сменился цвет или текст. Раньше смотрели только на цвет, и после нажатия «Не работает»
         * строка «разбираюсь, что не работает…» появлялась лишь через ~10 секунд, когда цвет менялся из-за начала поиска.
         */
        fun needsRefresh(tone: NotifyTone, lastTone: NotifyTone?, text: String, lastText: String): Boolean =
            tone != lastTone || (text.isNotBlank() && text != lastText)

        fun of(connected: Boolean, connecting: Boolean, note: String, sub: VpnSub = VpnSub.NONE): NotifyTone {
            val n = note.lowercase()
            return when {
                sub == VpnSub.BROKEN -> RED
                sub == VpnSub.NO_NETWORK || sub == VpnSub.SEARCHING -> YELLOW
                connecting -> YELLOW
                connected && (n.startsWith("нет связи") || n.contains("заблокирован")) -> RED
                connected && (n.contains("ищу") || n.contains("меняю") || n.contains("проверяю") || n.contains("разбираюсь")) -> YELLOW
                connected -> GREEN
                else -> YELLOW
            }
        }
    }
}
