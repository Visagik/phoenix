package com.phoenix.app

/**
 * Тип блокировок в текущей сети известен или нет. Определение работает только при включённом VPN, поэтому после запуска
 * приложения режим — прежний или «по умолчанию»; автоматическая перепроверка в таком режиме идёт впустую.
 */
object ModeGate {
    /** Сеть, в которой тип блокировок уже определён (или режим взят по памяти о знакомой сети); пусто — ещё нет. */
    @Volatile var knownNet: String = ""

    fun needsDetect(auto: Boolean, known: String, current: String, held: Boolean): Boolean =
        auto && !held && current.isNotEmpty() && known != current
}

/**
 * Замер шёл по мобильной сети, а «сеть» по NetInfo — Wi-Fi (Wi-Fi подключён, но без интернета, телефон тянет всё через мобильную, как
 * «умное переключение» Samsung). Журнал 22.11: белый результат мобильной сети записывался на Wi-Fi, режим переключался 54 раза за сутки,
 * каждый раз 12–59 с без связи. Пока так длится недолго, результат не засчитывается; затянулось — считается результатом мобильной сети.
 */
object NetMismatch {
    const val GRACE_MS = 90_000L

    /** [since] — когда расхождение началось (0 — не идёт). */
    fun ignore(measuredCellular: Boolean, nominalWifi: Boolean, since: Long, now: Long): Boolean =
        measuredCellular && nominalWifi && since > 0 && now - since in 0 until GRACE_MS
}

/**
 * «Белые списки» из чёрных по замеру через Wi-Fi, который сейчас дёргается (система предупредила, что сеть пропадёт, или сеть прыгала):
 * умирающий Wi-Fi даёт «0 зарубежных», это не блокировка. Журнал 22.11: три смены режима за 3 минуты, перерывы 38, 59 и 23 с. Сменить режим
 * на белые можно позже, когда сеть устоялась; обратное (на чёрные) не задерживается.
 */
object WobbleGuard {
    fun ignore(detected: BlockType, current: Mode, measuredWifi: Boolean, shaky: Boolean): Boolean =
        detected == BlockType.WHITELIST && current != Mode.WHITELIST && measuredWifi && shaky
}
