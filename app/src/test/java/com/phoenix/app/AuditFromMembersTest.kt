package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditFromMembersTest {
    private fun keys(range: IntRange) = range.map { "vless://k$it@h:1" }.toSet()

    @Test fun blacklistedSourcesAreLeftOut() {
        val m = linkedMapOf("a" to keys(1..10), "b" to keys(1..10), "c" to keys(1..10))
        val sets = SourceAudit.fromMembers(m, setOf("b"))
        assertEquals(listOf("a", "c"), sets.map { it.url })
        assertEquals(listOf(0, 1), sets.map { it.order })
    }

    @Test fun exactDuplicateIsFoundFromSavedLists() {
        val m = linkedMapOf("big" to keys(1..20), "copy" to keys(1..20), "other" to keys(100..120))
        val r = SourceAudit.analyze(SourceAudit.fromMembers(m, emptySet()))
        assertEquals(1, r.proposals.size)
        assertTrue(r.proposals[0].url == "copy" || r.proposals[0].url == "big")
    }
}
