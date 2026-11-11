package com.phoenix.app

/**
 * Какой тип конфига работает в текущей сети. История копится по серверам; здесь она складывается по типам (Reality, Trojan, Hysteria2…),
 * и тип, который в этой сети подводит, опускается в очереди, а тип, который выручает, поднимается. Нужно, чтобы не перебирать
 * сотни конфигов того типа, который режет именно этот оператор.
 */
object ProtocolLearning {
    const val MIN_SAMPLES = 6
    const val GOOD = 0.6
    const val POOR = 0.25

    /** Тип конфига по ссылке: reality, vless-tls, ws, grpc, trojan, vmess, ss, hysteria2, прочее. */
    fun kindOf(link: String): String {
        val l = link.trim()
        val reality = l.contains("security=reality")
        return when {
            l.startsWith("hysteria2://") || l.startsWith("hy2://") -> "hysteria2"
            l.startsWith("trojan://") -> "trojan"
            l.startsWith("vmess://") -> "vmess"
            l.startsWith("ss://") -> "ss"
            l.startsWith("vless://") -> when {
                reality -> "reality"
                l.contains("type=grpc") || l.contains("mode=grpc") -> "grpc"
                l.contains("type=ws") -> "ws"
                else -> "vless-tls"
            }
            else -> "other"
        }
    }

    data class Rate(val ok: Int, val fail: Int) {
        val total: Int get() = ok + fail
        val value: Double get() = if (total == 0) 0.5 else ok.toDouble() / total
    }

    /** Складывает опыт по типам: [stats] — пары (ссылка, статистика в этой сети). */
    fun rates(stats: List<Pair<String, HistStat?>>): Map<String, Rate> {
        val ok = HashMap<String, Int>()
        val fail = HashMap<String, Int>()
        for ((link, st) in stats) {
            if (st == null) continue
            val k = kindOf(link)
            ok[k] = (ok[k] ?: 0) + st.ok
            fail[k] = (fail[k] ?: 0) + st.fail
        }
        return (ok.keys + fail.keys).associateWith { Rate(ok[it] ?: 0, fail[it] ?: 0) }
    }

    /** 0 — тип здесь выручает, 1 — данных мало или средне, 2 — подводит. Тип сравнивается с остальными: если все плохи, различий нет. */
    fun bucket(kind: String, rates: Map<String, Rate>): Int {
        val r = rates[kind] ?: return 1
        if (r.total < MIN_SAMPLES) return 1
        val known = rates.values.filter { it.total >= MIN_SAMPLES }
        if (known.size < 2) return 1
        return when {
            r.value >= GOOD -> 0
            r.value < POOR && known.any { it !== r && it.value >= GOOD } -> 2
            else -> 1
        }
    }

    /** Строка для экрана: «Reality 82% (из 40), Trojan 20% (из 15)». */
    fun summary(rates: Map<String, Rate>, max: Int = 4): String =
        rates.entries.filter { it.value.total >= MIN_SAMPLES }
            .sortedByDescending { it.value.total }.take(max)
            .joinToString(", ") { "${title(it.key)} ${Math.round(it.value.value * 100)}% (из ${it.value.total})" }

    fun title(kind: String): String = when (kind) {
        "reality" -> "Reality"; "vless-tls" -> "VLESS+TLS"; "ws" -> "WebSocket"; "grpc" -> "gRPC"
        "trojan" -> "Trojan"; "vmess" -> "VMess"; "ss" -> "Shadowsocks"; "hysteria2" -> "Hysteria2"; else -> "прочее"
    }
}
