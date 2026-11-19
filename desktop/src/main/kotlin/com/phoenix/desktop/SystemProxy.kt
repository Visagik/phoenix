package com.phoenix.desktop

import java.util.concurrent.TimeUnit

/** Системный прокси рабочего стола через gsettings (GNOME, Cinnamon — Linux Mint, MATE и др.). */
object SystemProxy {
    private const val SCHEMA = "org.gnome.system.proxy"

    private fun gs(vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("gsettings") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() == 0) out else null
    } catch (_: Exception) {
        null
    }

    /** gsettings «'manual'» → manual */
    fun unquote(v: String): String = v.trim().removeSurrounding("'")

    val available: Boolean get() = gs("get", SCHEMA, "mode") != null

    private val KEYS = listOf(
        "$SCHEMA.socks" to "host", "$SCHEMA.socks" to "port",
        "$SCHEMA.http" to "host", "$SCHEMA.http" to "port",
        "$SCHEMA.https" to "host", "$SCHEMA.https" to "port",
        SCHEMA to "mode",
    )

    /** Что было в настройках до нас: ключ «схема key» → значение в виде, который принимает gsettings set. */
    fun snapshot(): Map<String, String>? {
        val m = LinkedHashMap<String, String>()
        for ((schema, key) in KEYS) m["$schema $key"] = gs("get", schema, key) ?: return null
        return m
    }

    fun enable(socksPort: Int, httpPort: Int): Boolean {
        val steps = listOf(
            arrayOf("set", "$SCHEMA.socks", "host", "'127.0.0.1'"),
            arrayOf("set", "$SCHEMA.socks", "port", socksPort.toString()),
            arrayOf("set", "$SCHEMA.http", "host", "'127.0.0.1'"),
            arrayOf("set", "$SCHEMA.http", "port", httpPort.toString()),
            arrayOf("set", "$SCHEMA.https", "host", "'127.0.0.1'"),
            arrayOf("set", "$SCHEMA.https", "port", httpPort.toString()),
            arrayOf("set", SCHEMA, "mode", "'manual'"),
        )
        return steps.all { gs(*it) != null }
    }

    /** Возвращает настройки как были; без снимка — просто выключает прокси. Режим пишется последним. */
    fun restore(before: Map<String, String>?): Boolean {
        if (before == null) return gs("set", SCHEMA, "mode", "'none'") != null
        var ok = true
        for ((k, v) in before.entries.sortedBy { it.key == "$SCHEMA mode" }) {
            val (schema, key) = k.split(' ', limit = 2)
            if (gs("set", schema, key, v) == null) ok = false
        }
        return ok
    }
}
