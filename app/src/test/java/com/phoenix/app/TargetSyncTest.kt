package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSyncTest {
    private fun t(id: String, servers: List<String> = emptyList(), date: String = "") =
        Target(id, TargetKind.APP, "pkg.$id", id, "https://$id.example/", Policy.DEFAULT, servers, date)

    @Test fun whiteModeGetsTargetsFromBlackMode() {
        val white = listOf(t("a"), t("b"))
        val black = listOf(t("a"), t("b"), t("c", listOf("vless://x"), "2026-11-22"), t("d"))
        val m = TargetSync.merged(white, listOf(black))!!
        assertEquals(listOf("a", "b", "c", "d"), m.map { it.id })
        // серверы режима не переносятся: у новой цели свои, подберутся сами
        assertTrue(m.first { it.id == "c" }.servers.isEmpty())
        assertEquals("", m.first { it.id == "c" }.verifiedAt)
    }

    @Test fun existingTargetsAreKeptAsIs() {
        val white = listOf(t("a", listOf("vless://w"), "2026-11-20"))
        val black = listOf(t("a", listOf("vless://b"), "2026-11-22"))
        assertNull(TargetSync.merged(white, listOf(black)))
    }

    @Test fun nothingMissingMeansNoChange() {
        assertNull(TargetSync.merged(listOf(t("a")), listOf(listOf(t("a")), emptyList())))
        assertNull(TargetSync.merged(emptyList(), listOf(emptyList())))
    }

    @Test fun duplicateAcrossOtherModesAddedOnce() {
        val m = TargetSync.merged(emptyList(), listOf(listOf(t("x")), listOf(t("x"), t("y"))))!!
        assertEquals(listOf("x", "y"), m.map { it.id })
    }
}
