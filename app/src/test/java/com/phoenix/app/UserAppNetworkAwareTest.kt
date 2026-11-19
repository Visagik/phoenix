package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAppNetworkAwareTest {
    private fun store(net: String): Pair<BypassStore, java.io.File> {
        val dir = java.io.File.createTempFile("uapp", "d").also { it.delete(); it.mkdirs() }
        return BypassStore(dir, Mode.BLACKLIST, net) to dir
    }

    private fun block(s: BypassStore, net: String, id: String) {
        var m = DirectLearn.load(s.learnFile)
        m = DirectLearn.record(m, net, id, false, 1L)
        m = DirectLearn.record(m, net, id, false, 2L)
        DirectLearn.save(s.learnFile, m)
    }

    @Test fun catalogContainsAiServices() {
        assertTrue(DirectCatalog.ENTRIES.any { it.id == "deepseek" && "com.deepseek.chat" in it.packages })
        assertTrue(DirectCatalog.ENTRIES.any { it.id == "qwen" && "ai.qwenlm.chat.android" in it.packages })
    }

    @Test fun userAppStaysInBypassWhileItOpensDirectly() {
        val (s, _) = store("wifi")
        s.setUserApps(listOf("com.deepseek.chat", "ai.qwenlm.chat.android", "com.example.mine"))
        val e = s.effective()
        assertTrue("com.deepseek.chat" in e)
        assertTrue("ai.qwenlm.chat.android" in e)
    }

    @Test fun userAppGoesThroughVpnWhereItIsBlocked() {
        val net = "mobile:test"
        val (s, _) = store(net)
        s.setUserApps(listOf("com.deepseek.chat", "ai.qwenlm.chat.android", "com.example.mine"))
        block(s, net, "deepseek")
        val e = s.effective()
        assertFalse("com.deepseek.chat" in e)
        assertTrue("ai.qwenlm.chat.android" in e)
        assertTrue("com.example.mine" in e)
    }

    @Test fun blockedStateIsPerNetwork() {
        val (s, dir) = store("mobile:test")
        s.setUserApps(listOf("com.deepseek.chat"))
        block(s, "mobile:test", "deepseek")
        assertFalse("com.deepseek.chat" in s.effective())
        assertTrue("com.deepseek.chat" in BypassStore(dir, Mode.BLACKLIST, "wifi").effective())
    }
}
