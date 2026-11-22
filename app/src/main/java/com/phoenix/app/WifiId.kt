package com.phoenix.app

import java.security.MessageDigest

/**
 * Отпечаток Wi-Fi-сети без геопозиции. Название сети (SSID) Android без разрешения на местоположение не отдаёт, зато параметры,
 * которые роутер выдаёт по DHCP (шлюз, DNS, подсеть, домен), доступны любому приложению. У разных сетей они обычно разные,
 * у одной и той же — стабильные. Хранится только короткий хеш. Слабое место: два роутера «из коробки» (192.168.1.1, одинаковый DNS)
 * дадут один отпечаток — для подсказки режима это терпимо, определение поправит.
 */
object WifiId {
    /** Подсеть без собственного адреса телефона (он меняется при каждой аренде): «192.168.1.0/24». */
    fun subnet(address: ByteArray, prefix: Int): String {
        if (prefix < 0 || prefix > address.size * 8) return ""
        val masked = address.copyOf()
        for (i in masked.indices) {
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            masked[i] = (masked[i].toInt() and mask).toByte()
        }
        return masked.joinToString(".") { (it.toInt() and 0xFF).toString() } + "/" + prefix
    }

    /** Короткий отпечаток; пустой, если данных нет совсем. */
    fun of(gateway: String?, dns: Collection<String>, domains: String?, subnets: Collection<String>): String {
        val g = gateway.orEmpty().trim()
        val d = dns.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        val s = subnets.filter { it.isNotEmpty() }.sorted()
        if (g.isEmpty() && d.isEmpty() && s.isEmpty()) return ""
        val raw = listOf(g, d.joinToString(","), domains.orEmpty().trim().lowercase(), s.joinToString(",")).joinToString("|")
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return h.take(3).joinToString("") { "%02x".format(it) }
    }

    /** Ключ места: «wifi#a1b2c3»; без отпечатка остаётся общий «wifi». */
    fun place(label: String, id: String): String = if (label == "wifi" && id.isNotEmpty()) "$label#$id" else label
}
