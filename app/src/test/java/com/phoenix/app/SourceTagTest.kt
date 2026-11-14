package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTagTest {
    private val key = { l: String -> ConfigIdentity.key(l) }
    private fun l(ip: Int, name: String) = "trojan://pw@203.0.113.$ip:443#$name"

    @Test fun sameNameInDifferentSourcesBecomesDifferentIdentities() {
        val out = SourceTag.apply(listOf("https://a/s" to listOf(l(1, "Germany 3")), "https://b/s" to listOf(l(2, "Germany 3"))), key)
        assertEquals(2, out.map(key).toSet().size)
    }

    @Test fun sameNameInsideOneSourceStaysOneIdentity() {
        val out = SourceTag.apply(listOf("https://a/s" to listOf(l(1, "Germany 3"), l(2, "Germany 3"))), key)
        assertEquals(1, out.map(key).toSet().size)
        assertEquals(listOf(l(1, "Germany 3"), l(2, "Germany 3")), out)
    }

    @Test fun firstSourceByAddressKeepsPlainNameRegardlessOfFetchOrder() {
        val a = "https://a/s" to listOf(l(1, "Germany 3"))
        val b = "https://b/s" to listOf(l(2, "Germany 3"))
        val k1 = SourceTag.apply(listOf(a, b), key).map(key)
        val k2 = SourceTag.apply(listOf(b, a), key).map(key).reversed()
        assertEquals(k1, k2)
        assertEquals(key(l(1, "Germany 3")), k1[0])
        assertFalse(key(l(2, "Germany 3")) == k1[1])
    }

    @Test fun noCollisionLeavesLinesUntouched() {
        val lines = listOf(l(1, "Poland 1"), l(2, "Turkey 2"))
        assertEquals(lines, SourceTag.apply(listOf("https://a/s" to lines), key))
    }

    @Test fun lineWithoutNameIsNotTagged() {
        assertEquals("vmess://abc", SourceTag.withTag("vmess://abc", "ab12"))
        assertTrue(SourceTag.withTag(l(1, "X"), "ab12").endsWith("(ab12)"))
    }
}
