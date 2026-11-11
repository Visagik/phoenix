package com.phoenix.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Страна по IPv4 без обращения к чужим сервисам: таблица диапазонов лежит в приложении (assets/geoip4.bin).
 * Источник — реестры RIR (ip-location-db, geo-whois-asn-country, CC0): это страна, на которую зарегистрирован адрес,
 * а не точное место сервера (у CDN может отличаться), поэтому название профиля по-прежнему главнее.
 */
class GeoIpDb private constructor(
    private val codes: List<String>,
    private val starts: IntArray,
    private val ends: IntArray,
    private val idx: ByteArray,
) {
    val size: Int get() = starts.size

    /** Страна адреса вида «1.2.3.4» или null (нет в таблице / не IPv4). */
    fun lookup(ip: String): String? {
        val v = parseIpv4(ip) ?: return null
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = starts[mid].toLong() and MASK
            when {
                v < s -> hi = mid - 1
                v > (ends[mid].toLong() and MASK) -> lo = mid + 1
                else -> return codes[idx[mid].toInt() and 0xFF]
            }
        }
        return null
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL
        private const val MAGIC = 0x47454F34 // "GEO4"

        fun parseIpv4(ip: String): Long? {
            val p = ip.trim().split('.')
            if (p.size != 4) return null
            var v = 0L
            for (x in p) {
                val n = x.toIntOrNull() ?: return null
                if (n !in 0..255) return null
                v = (v shl 8) or n.toLong()
            }
            return v
        }

        /** Запись таблицы: диапазоны (начало, конец, код страны) по возрастанию, без пересечений. */
        fun write(out: OutputStream, ranges: List<Triple<Long, Long, String>>) {
            val codes = ranges.map { it.third }.distinct().sorted()
            require(codes.size <= 255)
            val d = DataOutputStream(out.buffered())
            d.writeInt(MAGIC)
            d.writeInt(ranges.size)
            d.writeByte(codes.size)
            for (c in codes) d.writeBytes(c.padEnd(2).take(2))
            for (r in ranges) d.writeInt(r.first.toInt())
            for (r in ranges) d.writeInt(r.second.toInt())
            for (r in ranges) d.writeByte(codes.indexOf(r.third))
            d.flush()
        }

        fun read(input: InputStream): GeoIpDb {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "не таблица стран" }
            val n = d.readInt()
            val k = d.readUnsignedByte()
            val codes = List(k) { String(ByteArray(2).also { b -> d.readFully(b) }) }
            val starts = IntArray(n) { d.readInt() }
            val ends = IntArray(n) { d.readInt() }
            val idx = ByteArray(n).also { d.readFully(it) }
            return GeoIpDb(codes, starts, ends, idx)
        }
    }
}

/** Где взять таблицу (на Android — assets; задаётся при старте воркера), и загрузка по требованию. */
object GeoIp {
    @Volatile var source: (() -> InputStream)? = null

    fun load(): GeoIpDb? = try {
        source?.invoke()?.use { GeoIpDb.read(it) }
    } catch (_: Exception) {
        null
    }
}
