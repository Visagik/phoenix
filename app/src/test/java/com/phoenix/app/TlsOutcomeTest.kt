package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsOutcomeTest {
    @Test
    fun certificateInCauseChainCounts() {
        val e = javax.net.ssl.SSLHandshakeException("x").also { it.initCause(java.security.cert.CertificateException("bad")) }
        assertTrue(TlsOutcome.certProblem(e))
    }

    @Test
    fun plainHandshakeFailureDoesNot() {
        assertFalse(TlsOutcome.certProblem(javax.net.ssl.SSLHandshakeException("reset")))
        assertFalse(TlsOutcome.certProblem(java.io.IOException("timeout")))
        assertFalse(TlsOutcome.certProblem(null))
    }
}
