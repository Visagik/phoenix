package com.phoenix.app

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Пароль внутреннего SOCKS-входа ядра. Без него любое приложение телефона могло бы найти открытый порт на 127.0.0.1
 * и пустить свой трафик через наш сервер; пароль случайный на каждый запуск ядра и известен только приложению.
 */
class SocksAuth(val user: String, val pass: String) {
    companion object {
        fun random(): SocksAuth {
            val r = SecureRandom()
            fun token() = ByteArray(12).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return SocksAuth(token(), token())
        }
    }
}

/** Не более [limit] байт из потока (InputStream.readNBytes есть только с Android 13, а minSdk у нас 29). */
internal fun readUpTo(inp: InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 8192).coerceAtLeast(16))
    val buf = ByteArray(minOf(limit, 8192).coerceAtLeast(1))
    var left = limit
    while (left > 0) {
        val n = inp.read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
    return out.toByteArray()
}

/** Ответ на запрос через локальный SOCKS. [close] обязателен (закрывает соединение). */
class LocalResponse(val code: Int, val headers: Map<String, String>, private val body: InputStream, private val socket: Socket, val finalUrl: String = "", val hops: List<String> = emptyList()) : Closeable {
    fun stream(): InputStream = body

    /** Начало тела (для проверки страниц-заглушек): не больше [limit] байт. */
    fun head(limit: Int): String = try {
        readUpTo(body, limit).toString(Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Свой маленький клиент: SOCKS5 с паролем (RFC 1929) и простой GET поверх него. java.net.Proxy на Android умеет SOCKS только без пароля,
 * поэтому HttpURLConnection для закрытого паролем входа не годится.
 */
object LocalSocks {
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Причина последней неудачи (этап + исключение): для журнала, иначе «нет связи» не отличить от сломанного пароля или TLS. */
    @Volatile var lastError: String? = null
        private set
    private val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun count(key: String) = counts.getOrPut(key) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    private fun fail(stage: String, e: Exception) {
        lastError = "$stage: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
        count(stage)
    }

    /** Итог запросов через внутренний SOCKS с прошлого вызова (и обнуление): для одной строки журнала в конце прогона. null — запросов не было. */
    fun statsLine(): String? {
        val ok = counts.remove("ok")?.get() ?: 0
        val bad = listOf("SOCKS", "TLS", "HTTP").associateWith { counts.remove(it)?.get() ?: 0 }
        if (ok == 0 && bad.values.all { it == 0 }) return null
        val last = lastError
        return "SOCKS: запросов через внутренний вход: удачных $ok, ошибок ${bad.values.sum()} (SOCKS ${bad["SOCKS"]}, TLS ${bad["TLS"]}, HTTP ${bad["HTTP"]})" +
            (if (bad.values.sum() > 0 && last != null) "; последняя: $last" else "")
    }

    /** Открывает TCP-соединение до [host]:[destPort] через SOCKS на 127.0.0.1:[port]. */
    fun connect(port: Int, auth: SocksAuth, host: String, destPort: Int, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            fun readN(n: Int): ByteArray {
                val b = ByteArray(n)
                var got = 0
                while (got < n) {
                    val r = inp.read(b, got, n - got)
                    if (r < 0) throw IOException("SOCKS: соединение закрыто")
                    got += r
                }
                return b
            }
            out.write(byteArrayOf(5, 1, 2)) // версия 5, один метод: логин и пароль
            out.flush()
            val method = readN(2)
            if (method[0] != 5.toByte() || method[1] != 2.toByte()) throw IOException("SOCKS: метод с паролем не принят")
            val u = auth.user.toByteArray()
            val p = auth.pass.toByteArray()
            out.write(ByteArrayOutputStream().apply {
                write(1); write(u.size); write(u); write(p.size); write(p)
            }.toByteArray())
            out.flush()
            if (readN(2)[1] != 0.toByte()) throw IOException("SOCKS: неверный пароль")

            val req = ByteArrayOutputStream()
            req.write(byteArrayOf(5, 1, 0))
            val bare = host.trim('[', ']')
            when {
                ipv4.matches(bare) -> {
                    req.write(1); req.write(InetAddress.getByName(bare).address)
                }
                bare.contains(':') -> {
                    req.write(4); req.write(InetAddress.getByName(bare).address)
                }
                else -> {
                    val h = bare.toByteArray()
                    if (h.isEmpty() || h.size > 255) throw IOException("SOCKS: плохое имя узла")
                    req.write(3); req.write(h.size); req.write(h)
                }
            }
            req.write(destPort shr 8); req.write(destPort and 0xFF)
            out.write(req.toByteArray())
            out.flush()
            val rep = readN(4)
            if (rep[1] != 0.toByte()) throw IOException("SOCKS: отказ ${rep[1]}")
            when (rep[3].toInt()) {
                1 -> readN(4 + 2)
                4 -> readN(16 + 2)
                3 -> readN((readN(1)[0].toInt() and 0xFF) + 2)
                else -> throw IOException("SOCKS: непонятный ответ")
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            fail("SOCKS", e)
            try {
                s.close()
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** GET по http/https через SOCKS-вход ядра. Заголовки в ключах — в нижнем регистре. */
    fun get(
        port: Int,
        auth: SocksAuth,
        url: String,
        connectTimeoutMs: Int = 6_000,
        readTimeoutMs: Int = 6_000,
        follow: Boolean = false,
        userAgent: String = "Mozilla/5.0 (Phoenix)",
        maxRedirects: Int = 5,
        sslFactory: SSLSocketFactory? = null, // для тестов (свой сертификат); на устройстве — системные доверенные
    ): LocalResponse {
        var current = url
        val jar = SimpleCookies()
        val trail = ArrayList<String>()
        for (hop in 0..maxRedirects) {
            val uri = URI(current)
            val https = uri.scheme.equals("https", true)
            if (!https && !uri.scheme.equals("http", true)) throw IOException("нужен http или https")
            val host = uri.host ?: throw IOException("в адресе нет узла")
            val destPort = if (uri.port > 0) uri.port else if (https) 443 else 80
            var sock = connect(port, auth, host, destPort, connectTimeoutMs)
            try {
                if (https) {
                    sock.soTimeout = readTimeoutMs
                    val ssl = (sslFactory ?: SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(sock, host.trim('[', ']'), destPort, true) as SSLSocket
                    val ps = ssl.sslParameters
                    ps.endpointIdentificationAlgorithm = "HTTPS" // имя в сертификате проверяется
                    ssl.sslParameters = ps
                    ssl.startHandshake()
                    sock = ssl
                }
                sock.soTimeout = readTimeoutMs
                trail += host + (uri.rawPath?.takeIf { it.isNotEmpty() && it != "/" }?.let { " " + it.take(40) } ?: "")
                val cookieLine = if (follow) jar.headerFor(host.trim('[', ']')) else ""
                val path = (uri.rawPath.takeIf { !it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val hostHeader = if (uri.port > 0) "$host:${uri.port}" else host
                sock.getOutputStream().write(
                    ("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: $userAgent\r\nAccept: */*\r\n" +
                        "Accept-Encoding: identity\r\n" + (if (cookieLine.isNotEmpty()) "Cookie: $cookieLine\r\n" else "") + "Connection: close\r\n\r\n").toByteArray()
                )
                sock.getOutputStream().flush()
                val inp = java.io.BufferedInputStream(sock.getInputStream())
                val status = readLine(inp) ?: throw IOException("пустой ответ")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("непонятный ответ: $status")
                val headers = HashMap<String, String>()
                val setCookies = ArrayList<String>()
                val budget = intArrayOf(MAX_HEADERS_TOTAL)
                while (true) {
                    val line = readLine(inp, strict = false, budget = budget) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        val name = line.substring(0, i).trim().lowercase()
                        headers[name] = line.substring(i + 1).trim()
                        if (name == "set-cookie" && setCookies.size < 40) setCookies += line.substring(i + 1).trim()
                    }
                }
                if (follow) for (c in setCookies) jar.add(host.trim('[', ']'), c)
                val location = headers["location"]
                if (follow && code in listOf(301, 302, 303, 307, 308) && location != null && hop < maxRedirects) {
                    sock.close()
                    current = uri.resolve(location).toString()
                    continue
                }
                val body: InputStream = if (headers["transfer-encoding"]?.contains("chunked", true) == true) ChunkedInputStream(inp) else inp
                count("ok")
                return LocalResponse(code, headers, body, sock, current, trail)
            } catch (e: Exception) {
                fail(if (https && sock !is SSLSocket) "TLS" else "HTTP", e)
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                throw if (e is IOException) e else IOException(e.message, e)
            }
        }
        throw IOException("слишком много перенаправлений")
    }

    /** Предел для строки статуса и для суммы заголовков. */
    internal const val MAX_LINE = 16_384
    internal const val MAX_HEADERS_TOTAL = 262_144

    /**
     * Строка ответа. Начало (до [MAX_LINE]) сохраняется, хвост длинной строки (например, огромный Set-Cookie у Google) читается и отбрасывается:
     * нужные заголовки (Location, Content-Length, Transfer-Encoding) короткие. Строка статуса длиннее предела — ошибка ([strict]).
     * [budget] — общий остаток байт заголовков.
     */
    internal fun readLine(inp: InputStream, strict: Boolean = true, budget: IntArray? = null): String? {
        val sb = StringBuilder()
        var any = false
        while (true) {
            val c = inp.read()
            if (c < 0) return if (!any) null else sb.toString()
            any = true
            if (budget != null && --budget[0] < 0) throw IOException("слишком большие заголовки ответа")
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) {
                if (strict) throw IOException("слишком длинная строка заголовка")
                continue
            }
            sb.append(c.toChar())
        }
    }

    /** Тело с Transfer-Encoding: chunked. */
    private class ChunkedInputStream(private val inp: InputStream) : InputStream() {
        private var left = 0
        private var done = false

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (left == 0) {
                val line = readLine(inp)?.substringBefore(';')?.trim() ?: return -1.also { done = true }
                left = line.toIntOrNull(16) ?: throw IOException("плохой размер блока")
                if (left == 0) {
                    done = true
                    return -1
                }
            }
            val n = inp.read(b, off, minOf(len, left))
            if (n < 0) return -1
            left -= n
            if (left == 0) readLine(inp) // конец блока
            return n
        }
    }
}


/**
 * Минимальный набор cookie для цепочки перенаправлений одного запроса (без срока жизни, путей и флагов безопасности).
 * Нужен потому, что Google при первом заходе ставит cookie на промежуточном адресе и без неё гоняет по кругу
 * (журнал 06.11: gemini.google.com отвечал 302 после 5 переходов, и проверка не видела настоящую страницу).
 */
class SimpleCookies {
    private data class C(val domain: String, val name: String, val value: String)
    private val items = ArrayList<C>()

    fun add(host: String, setCookie: String) {
        val parts = setCookie.split(';')
        val nv = parts.firstOrNull()?.trim() ?: return
        val eq = nv.indexOf('=')
        if (eq <= 0) return
        val name = nv.substring(0, eq).trim()
        val value = nv.substring(eq + 1).trim()
        var domain = host.lowercase()
        var expired = false
        for (p in parts.drop(1)) {
            val a = p.trim()
            if (a.startsWith("domain=", true)) a.substring(7).trim().trimStart('.').lowercase().takeIf { it.isNotEmpty() && (host.lowercase() == it || host.lowercase().endsWith(".$it")) }?.let { domain = it }
            if (a.equals("max-age=0", true) || a.startsWith("max-age=-", true)) expired = true
        }
        items.removeAll { it.domain == domain && it.name == name }
        if (!expired && items.size < 60 && value.length <= 4096) items += C(domain, name, value)
    }

    fun headerFor(host: String): String {
        val h = host.lowercase()
        return items.filter { h == it.domain || h.endsWith("." + it.domain) }.joinToString("; ") { "${it.name}=${it.value}" }
    }
}
