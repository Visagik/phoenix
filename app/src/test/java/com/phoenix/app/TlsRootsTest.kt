package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TlsRootsTest {
    private fun raw(n: String): ByteArray = File("app/src/main/res/raw/$n.pem").readBytes()

    private fun cert(n: String): X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(raw(n))) as X509Certificate

    private val rejectAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) { throw CertificateException("Trust anchor for certification path not found.") }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test fun bundledRootsMatchFingerprints() {
        val l = TlsRoots.load(mapOf("isrg_root_x1" to { ByteArrayInputStream(raw("isrg_root_x1")) }, "isrg_root_x2" to { ByteArrayInputStream(raw("isrg_root_x2")) }))
        assertEquals(2, l.certs.size)
        assertTrue(l.rejected.isEmpty())
    }

    @Test fun swappedFileIsRejected() {
        // под именем X1 лежит X2: отпечаток не совпадает, корень не берётся
        val l = TlsRoots.load(mapOf("isrg_root_x1" to { ByteArrayInputStream(raw("isrg_root_x2")) }))
        assertEquals(0, l.certs.size)
        assertEquals(listOf("isrg_root_x1"), l.rejected)
        val junk = TlsRoots.load(mapOf("isrg_root_x2" to { ByteArrayInputStream("not a cert".toByteArray()) }))
        assertEquals(listOf("isrg_root_x2"), junk.rejected)
    }

    @Test fun ownRootAcceptsWhenSystemRejects() {
        val x1 = cert("isrg_root_x1")
        TlsRoots.trustManager(listOf(x1), rejectAll).checkServerTrusted(arrayOf(x1), "RSA")
    }

    @Test fun unknownIssuerIsNamedInTheError() {
        val x1 = cert("isrg_root_x1")
        val e = try { TlsRoots.trustManager(emptyList(), rejectAll).checkServerTrusted(arrayOf(x1), "RSA"); null } catch (e: CertificateException) { e }
        assertTrue(e is TlsRoots.UntrustedIssuerException)
        assertEquals("ISRG Root X1", (e as TlsRoots.UntrustedIssuerException).issuer)
        assertEquals("ISRG Root X1", TlsRoots.issuerNote(java.io.IOException("x", e)))
        assertNull(TlsRoots.issuerNote(java.io.IOException("plain")))
    }

    @Test fun failureTextShowsIssuer() {
        val x1 = cert("isrg_root_x1")
        val e = try { TlsRoots.trustManager(emptyList(), rejectAll).checkServerTrusted(arrayOf(x1), "RSA"); null } catch (e: CertificateException) { e!! }
        val ssl = javax.net.ssl.SSLHandshakeException("chain validation failed").also { it.initCause(e) }
        assertTrue(FetchFailure.describe(ssl, 900, 0).contains("ISRG Root X1"))
        assertTrue(!FetchFailure.describe(javax.net.ssl.SSLHandshakeException("x"), 900, 0).contains("издател"))
    }
}
