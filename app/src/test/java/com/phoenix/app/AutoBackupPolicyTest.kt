package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPolicyTest {
    private val day = 24L * 3600 * 1000

    @Test fun dueWhenNeverMadeWeekPassedOrClockWentBack() {
        assertTrue(AutoBackupPolicy.due(0L, 1000L))
        assertFalse(AutoBackupPolicy.due(1000L, 1000L + 6 * day))
        assertTrue(AutoBackupPolicy.due(1000L, 1000L + 7 * day))
        assertTrue(AutoBackupPolicy.due(10_000L, 5_000L))
    }

    @Test fun keepsThreeNewestByDateInName() {
        val names = listOf("phoenix-auto-2026-11-01.txt", "phoenix-auto-2026-11-15.txt", "phoenix-auto-2026-11-08.txt", "phoenix-auto-2026-10-25.txt", "other.txt")
        assertEquals(listOf("phoenix-auto-2026-10-25.txt"), AutoBackupPolicy.toDelete(names))
    }

    @Test fun nothingToDeleteWhenFewFiles() {
        assertTrue(AutoBackupPolicy.toDelete(listOf("phoenix-auto-2026-11-01.txt")).isEmpty())
    }

    @Test fun fileNameHasDate() {
        assertEquals("phoenix-auto-2026-11-19.txt", AutoBackupPolicy.fileName("2026-11-19"))
    }
}
