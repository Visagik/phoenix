package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsweredDespiteTest {
    private val ru = BlockDetector.RUSSIAN.first()
    private val foreign = BlockDetector.FOREIGN.first()

    private fun certError() = javax.net.ssl.SSLHandshakeException("chain validation failed").also {
        it.initCause(java.security.cert.CertificateException("x", java.security.cert.CertPathValidatorException("Trust anchor not found")))
    }

    @Test fun russianSiteWithUntrustedCertAnswered() = assertTrue(TlsOutcome.answeredDespite(ru, certError()))

    @Test fun foreignSiteWithCertErrorDidNot() = assertFalse(TlsOutcome.answeredDespite(foreign, certError()))

    @Test fun russianHandshakeResetWithoutCertDidNot() = assertFalse(TlsOutcome.answeredDespite(ru, javax.net.ssl.SSLHandshakeException("connection reset")))

    @Test fun timeoutDidNot() = assertFalse(TlsOutcome.answeredDespite(ru, java.net.SocketTimeoutException("timeout")))
}
