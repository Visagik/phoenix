package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetSiteTest {
    private val shared = "vless://aaaa@1.2.3.4:443?security=tls#Shared"
    private fun t(id: String, url: String) = Target(id, TargetKind.SITE, id, id, url, servers = listOf(shared))

    @Test fun onlyTargetsOfTheSameSiteLoseTheServer() {
        val targets = listOf(
            t("telegram", "https://web.telegram.org/"),
            t("discord", "https://discord.com/"),
            t("notebook", "https://notebooklm.google.com/"),
        )
        assertEquals(listOf("notebook"), TargetSite.affected(targets, shared, "https://notebook.google/").map { it.id })
        assertEquals(listOf("telegram"), TargetSite.affected(targets, shared, "https://web.telegram.org/").map { it.id })
    }

    @Test fun geminiAndNotebookAreDifferentSites() {
        val targets = listOf(t("gemini", "https://gemini.google.com/"), t("notebook", "https://notebooklm.google.com/"))
        assertEquals(listOf("gemini"), TargetSite.affected(targets, shared, "https://gemini.google.com/").map { it.id })
    }
}
