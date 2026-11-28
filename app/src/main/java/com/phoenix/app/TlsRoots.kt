package com.phoenix.app

import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Корневые сертификаты Let's Encrypt (ISRG Root X1 и X2) для Android 6: в его системном хранилище их нет, и
 * https-запросы к сайтам с такими сертификатами падают. Корни добавляются к системным, а не вместо них, проверка
 * цепочки и имени узла остаётся полной. Чужих корней (в том числе российского Минцифры) здесь нет и не будет.
 * Чистая логика: ресурсы читает вызывающий (см. LiteEnv.installTls).
 */
object TlsRoots {
    /** Ожидаемые SHA-256 отпечатки (совпадают с опубликованными на letsencrypt.org/certificates). Файл с другим отпечатком не берётся. */
    val EXPECTED: Map<String, String> = mapOf(
        "isrg_root_x1" to "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",
        "isrg_root_x2" to "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
    )

    /** Фабрика с добавленными корнями; null, пока [install] не вызывали (тогда везде системная). */
    @Volatile var factory: SSLSocketFactory? = null

    /** Ошибка проверки цепочки с именем издателя последнего сертификата: по нему видно, чего именно не хватает. */
    class UntrustedIssuerException(message: String, val issuer: String, cause: Throwable?) : CertificateException(message, cause)

    fun sha256Hex(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02X".format(it) }

    class Loaded(val certs: List<X509Certificate>, val rejected: List<String>)

    /** Читает PEM/DER-ресурсы, оставляет только те, чей отпечаток совпал с [EXPECTED]. */
    fun load(sources: Map<String, () -> InputStream>): Loaded {
        val ok = ArrayList<X509Certificate>()
        val bad = ArrayList<String>()
        val cf = CertificateFactory.getInstance("X.509")
        for ((name, open) in sources) {
            try {
                val c = open().use { cf.generateCertificate(it) } as X509Certificate
                if (sha256Hex(c.encoded) == EXPECTED[name]) ok += c else bad += name
            } catch (_: Exception) {
                bad += name
            }
        }
        return Loaded(ok, bad)
    }

    fun systemTrustManager(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun onlyTrustManager(extra: List<X509Certificate>): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        extra.forEachIndexed { i, c -> ks.setCertificateEntry("phoenix-root-$i", c) }
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Сначала системные корни; не прошло — наши. Если не прошло и там, исключение называет издателя. */
    fun trustManager(extra: List<X509Certificate>, system: X509TrustManager = systemTrustManager()): X509TrustManager {
        val own = if (extra.isEmpty()) null else onlyTrustManager(extra)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    if (own != null) {
                        try { own.checkServerTrusted(chain, authType); return } catch (_: CertificateException) { }
                    }
                    val issuer = issuerName(chain)
                    throw UntrustedIssuerException("${e.message?.take(100)}; издатель: $issuer", issuer, e)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra
        }
    }

    /** Имя издателя последнего сертификата цепочки (того, которому не нашлось корня): «CN» или всё имя. */
    fun issuerName(chain: Array<X509Certificate>): String {
        val top = chain.lastOrNull() ?: return "?"
        val full = top.issuerX500Principal.getName(javax.security.auth.x500.X500Principal.RFC1779)
        return Regex("CN=(\"[^\"]*\"|[^,]*)").find(full)?.groupValues?.get(1)?.trim('"')?.trim()?.ifEmpty { null } ?: full.take(60)
    }

    fun socketFactory(tm: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), null)
        return ctx.socketFactory
    }

    /** Строка для журнала об ошибке сертификата: издатель, если он известен. Ищет по цепочке причин. */
    fun issuerNote(e: Throwable): String? {
        var c: Throwable? = e
        var n = 0
        while (c != null && n++ < 6) {
            if (c is UntrustedIssuerException) return c.issuer
            c = c.cause?.takeIf { it !== c }
        }
        return null
    }
}
