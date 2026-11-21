package com.phoenix.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCheckerInnerTest {
    private val link = "vless://11111111-2222-3333-4444-555555555555@example.org:443?security=tls&sni=example.org&type=ws&path=%2F#t"
    private val services = (1..8).map { CheckService("S$it", "Svc$it", "https://s$it.example/") }

    private fun run(inner: Int): CheckResult {
        val probe = { _: String, url: String -> if (url.contains("s3.") || url.contains("s6.")) -1L else 25L }
        return runBlocking { ServiceChecker(probe, parallel = 1, inner = inner).check(listOf(link), services, "2026-11-21") }.values.first()
    }

    @Test
    fun parallelServicesGiveSameResultAsSequential() {
        val one = run(1)
        val three = run(3)
        assertEquals(one.services, three.services)
        assertEquals(8, three.services.size)
        assertTrue(three.services["S1"]!! >= 0)
        assertEquals(CheckServices.FAILED, three.services["S3"])
    }
}
