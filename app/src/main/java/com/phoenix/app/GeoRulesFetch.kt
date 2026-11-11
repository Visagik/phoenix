package com.phoenix.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Файл правил регионов (см. [GeoRules]): подхватывается при запуске; если в настройках указана ссылка https, перед этим скачивается заново. */
object GeoRulesFetch {
    const val FILE = "geogate_rules.txt"

    fun refresh(context: Context, storage: Storage? = null) {
        val file = File(context.filesDir, FILE)
        val url = AppSettings(context).geoRulesUrl.trim()
        if (url.startsWith("https://")) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", "Phoenix/1.0")
                if (c.responseCode == 200) {
                    val text = c.inputStream.use { readUpTo(it, 65_536) }.toString(Charsets.UTF_8)
                    if (GeoRules.parse(text) != null) {
                        file.writeTextAtomic(text)
                        storage?.log("GEO: правила регионов обновлены по ссылке")
                    } else storage?.log("GEO: по ссылке не правила регионов (нет строк host:) — оставляю прежние")
                }
            } catch (_: Throwable) {
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) { }
            }
        }
        val own = GeoRules.reload(file)
        storage?.log(if (own) "GEO: действуют правила регионов из файла (сайтов ${GeoRules.current.hosts.size})" else "GEO: действуют встроенные правила регионов (сайтов ${GeoRules.current.hosts.size})")
    }
}
