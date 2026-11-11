package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSourcesTest {
    @Test fun blobBecomesRaw() {
        assertEquals(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt",
            DefaultSources.raw("https://github.com/igareck/vpn-configs-for-russia/blob/main/BLACK_VLESS_RUS.txt"),
        )
        assertEquals("https://example.com/a.txt", DefaultSources.raw("https://example.com/a.txt"))
    }

    @Test fun listsPerMode() {
        assertEquals(5, DefaultSources.forMode(Mode.BLACKLIST).size)
        assertEquals(11, DefaultSources.forMode(Mode.WHITELIST).size)
        assertEquals(DefaultSources.WHITE.size, DefaultSources.WHITE.toSet().size)
        assertTrue(DefaultSources.WHITE.none { DefaultSources.isExcluded(it) })
        assertTrue(DefaultSources.BLACK.all { it.startsWith("https://") })
    }

    @Test fun durevIsExcluded() {
        assertTrue(DefaultSources.isExcluded("https://your-durev.com/sub/abc/white/"))
        assertTrue(!DefaultSources.isExcluded("https://raw.githubusercontent.com/x/y/main/z.txt"))
    }
}
