package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsModeTest {
    private val installed = listOf("org.telegram.messenger", "com.google.android.youtube", "com.phoenix.app", "com.bank.app")

    @Test fun onlyInstalledAndNotSelf() {
        val a = AppsMode.allowed(setOf("org.telegram.messenger", "not.installed", "com.phoenix.app"), emptySet(), installed, "com.phoenix.app")
        assertEquals(setOf("org.telegram.messenger"), a)
    }

    @Test fun targetAppsAreAddedAutomatically() {
        val a = AppsMode.allowed(setOf("org.telegram.messenger"), setOf("com.google.android.youtube", "gone.app"), installed, "com.phoenix.app")
        assertEquals(setOf("org.telegram.messenger", "com.google.android.youtube"), a)
    }

    @Test fun emptyResultMeansModeIsNotApplied() {
        assertTrue(AppsMode.allowed(setOf("not.installed"), emptySet(), installed, "com.phoenix.app").isEmpty())
        assertTrue(AppsMode.allowed(emptySet(), emptySet(), installed, "com.phoenix.app").isEmpty())
    }

    @Test fun storageRoundtripAndClamp() {
        val s = setOf("b.app", "a.app")
        assertEquals(s, AppsMode.decode(AppsMode.encode(s)))
        assertEquals(AppsMode.ALL_EXCEPT, AppsMode.clamp(5))
        assertEquals(AppsMode.ONLY_SELECTED, AppsMode.clamp(1))
    }
}
