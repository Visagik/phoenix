package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceSummaryTest {
    @Test
    fun shortNameKeepsHostAndOwnerButNotKeys() {
        assertEquals("raw.githubusercontent.com/igareck", SourceStats.shortName("https://raw.githubusercontent.com/igareck/vpn/main/list.txt"))
        val n = SourceStats.shortName("https://your-durev.com/sub/Abc123SECRETkey9999")
        assertEquals("your-durev.com/sub", n)
        assertFalse(n.contains("SECRET"))
        assertEquals("example.org", SourceStats.shortName("https://example.org/a1b2c3d4e5f6g7h8i9j0k1l2m3"))
        assertEquals("example.org", SourceStats.shortName("https://user:pw@example.org/"))
    }

    @Test
    fun summaryListsBestFirstAndMarksUnchecked() {
        val line = SourceStats.summaryLine(listOf(
            SourceStat("https://a.example/x/1", 50, 2, 8),
            SourceStat("https://b.example/y/1", 40, 9, 1),
            SourceStat("https://c.example/z/1", 10, 0, 0),
        ), limit = 3)
        assertEquals("b.example/y 90% (9/10) · a.example/x 20% (2/10) · c.example/z ?", line)
    }
}
