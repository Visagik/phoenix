package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Release3933Test {
    @Test fun appLinkRulesNormalizeAndDedup() {
        val r = AppLinkDomains.rules(listOf("www.sberbank.ru", "*.sberbank.ru", "online.sberbank.ru", "SBER.ru", "  gosuslugi.ru "))
        assertEquals(listOf("domain:sberbank.ru", "domain:online.sberbank.ru", "domain:sber.ru", "domain:gosuslugi.ru"), r)
    }

    @Test fun appLinkRulesNeverBypassBlockedOrGeneric() {
        val r = AppLinkDomains.rules(listOf("t.me", "web.telegram.org", "youtube.com", "m.youtube.com", "www.google.com", "play.google.com", "vk.com", "10.0.0.1", "localhost", "*"))
        assertEquals(listOf("domain:vk.com"), r)
        assertFalse(r.any { "youtube" in it || "google" in it || "t.me" in it })
    }

    @Test fun appLinkRulesCapped() {
        val many = (1..1000).map { "site$it.example.ru" }
        assertEquals(AppLinkDomains.MAX_DOMAINS, AppLinkDomains.rules(many).size)
    }

    @Test fun bypassSiteRulesIncludeAppDomainsWhenEnabled() {
        val d = java.nio.file.Files.createTempDirectory("byp").toFile()
        val b = BypassStore(d, Mode.WHITELIST)
        val app = listOf("domain:mybank.ru")
        assertTrue("domain:mybank.ru" in b.siteRules(app))
        b.appLinksEnabled = false
        assertFalse("domain:mybank.ru" in b.siteRules(app))
        b.appLinksEnabled = true
        assertTrue("domain:mybank.ru" in b.siteRules(app))
        assertEquals(b.siteRules(app).distinct(), b.siteRules(app))
    }
}
