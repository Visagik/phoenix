package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test fun currentReleaseHasItems() {
        val e = WhatsNew.forVersion("3.9.68")
        assertNotNull(e)
        assertTrue(e!!.items.size in 3..8)
        assertTrue(e.items.none { it.isBlank() })
    }

    @Test fun versionLookupIgnoresPrefix() {
        assertEquals("3.9.66", WhatsNew.forVersion("v3.9.66")?.version)
        assertNull(WhatsNew.forVersion("1.0.0"))
    }

    @Test fun entriesAreNewestFirstAndUnique() {
        val v = WhatsNew.ENTRIES.map { it.version }
        assertEquals(v.size, v.toSet().size)
        for (i in 0 until v.size - 1) assertTrue("${v[i]} должна быть новее ${v[i + 1]}", UpdateCheck.isNewer(v[i], v[i + 1]))
    }

    @Test fun updatedUserSeesDialogOnce() {
        assertTrue(WhatsNew.shouldShow("", "3.9.67", fresh = false))        // обновился до появления окна
        assertTrue(WhatsNew.shouldShow("3.9.66", "3.9.67", fresh = false))
        assertFalse(WhatsNew.shouldShow("3.9.67", "3.9.67", fresh = false)) // уже видел
        assertFalse(WhatsNew.shouldShow("v3.9.67", "3.9.67", fresh = false))
    }

    @Test fun freshInstallAndDowngradeGetNoDialog() {
        assertFalse(WhatsNew.shouldShow("", "3.9.67", fresh = true))
        assertFalse(WhatsNew.shouldShow("3.9.68", "3.9.67", fresh = false)) // откат на старую сборку
    }

    @Test fun versionWithoutItemsGetsNoDialog() {
        assertFalse(WhatsNew.shouldShow("3.9.66", "3.9.99", fresh = false))
    }
}
