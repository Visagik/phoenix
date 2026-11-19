package com.phoenix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceListTest {
    @Test fun realListPasses() {
        assertTrue(FetchPolicy.looksLikeSourceList("https://a.example/x.txt\nhttps://b.example/y.txt\n# комментарий"))
    }

    @Test fun portalPageIsRejected() {
        assertFalse(FetchPolicy.looksLikeSourceList("<html><body><a href=\"https://login.example\">Войти</a>\nhttps://x.example\nhttps://y.example</body></html>"))
        assertFalse(FetchPolicy.looksLikeSourceList("Sign in to Wi-Fi"))
        assertFalse(FetchPolicy.looksLikeSourceList("https://only-one.example"))
    }
}
