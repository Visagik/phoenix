package com.phoenix.app

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class Release3942Test {
    @Test fun steamPackagesAreInOpenServicesAndCatalog() {
        assertTrue("com.valvesoftware.android.steam.community" in OpenServices.PACKAGES)
        assertTrue("com.valvesoftware.steamlink" in OpenServices.PACKAGES)
        val steam = DirectCatalog.ENTRIES.first { it.id == "steam" }
        assertTrue("com.valvesoftware.steamlink" in steam.packages)
    }

    @Test fun directStatNeedsTwoFailuresInARow() {
        var s = DirectStat()
        s = s.next(false)
        assertFalse(s.blocked)
        s = s.next(true).next(false)
        assertFalse(s.blocked)
        s = s.next(false)
        assertTrue(s.blocked)
    }

    @Test fun effectivePackagesIncludeOpenServicesWhenEnabled() {
        val dir = java.io.File.createTempFile("bps", "d").also { it.delete(); it.mkdirs() }
        val store = BypassStore(dir)
        store.openEnabled = true
        assertTrue("com.valvesoftware.android.steam.community" in store.effective())
        store.openEnabled = false
        assertFalse("com.valvesoftware.android.steam.community" in store.effective())
        store.setUserApps(setOf("com.example.mine"))
        assertTrue("com.example.mine" in store.effective())
        dir.deleteRecursively()
    }
}
