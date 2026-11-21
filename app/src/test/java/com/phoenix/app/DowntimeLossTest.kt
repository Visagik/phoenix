package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DowntimeLossTest {
    @Test fun lossKindHasOwnTitleAndIsSummed() {
        assertEquals("сбой связи", DowntimeStats.kindTitle(DowntimeStats.LOSS))
        val f = java.io.File.createTempFile("down", ".tsv"); f.deleteOnExit()
        val st = DowntimeStats(f)
        assertTrue(st.add(DowntimeStats.LOSS, 96, "2026-11-20"))
        assertTrue(st.add(DowntimeStats.FAILOVER, 14, "2026-11-20"))
        val s = st.summary("2026-11-20")
        assertTrue(s, s.contains("перерывов 2"))
        assertTrue(s, s.contains("сбой связи 1"))
    }
}
