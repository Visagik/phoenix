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
        assertEquals(12, DefaultSources.forMode(Mode.BLACKLIST).size)
        assertEquals(40, DefaultSources.forMode(Mode.WHITELIST).size)
        assertEquals(DefaultSources.WHITE.size, DefaultSources.WHITE.toSet().size)
        assertTrue(DefaultSources.WHITE.none { DefaultSources.isExcluded(it) })
        assertTrue(DefaultSources.BLACK.all { it.startsWith("https://") })
    }

    @Test fun durevIsExcluded() {
        assertTrue(DefaultSources.isExcluded("https://your-durev.com/sub/abc/white/"))
        assertTrue(!DefaultSources.isExcluded("https://raw.githubusercontent.com/x/y/main/z.txt"))
    }

    @Test fun yandexTranslatorUrlIsKeptAsIs() {
        val y = DefaultSources.WHITE.first { it.startsWith("https://translate.yandex.ru/translate?url=") }
        assertTrue(y.contains("&lang=de-de"))
        assertTrue(!DefaultSources.isExcluded(y))
        assertTrue(!DefaultSources.isExcluded(y, emptyList()))
        assertEquals(y, DefaultSources.raw(y))
        // ключ состояния источника: строка «url|active|...» разбирается обратно в тот же адрес
        assertEquals(y, SourceState.parse(SourceState(true, 1, "2026-11-24", "FAIL:404", 0).toLine(y))!!.first)
    }

    @Test fun removedAndReplacedWhiteSources() {
        assertTrue(DefaultSources.WHITE.none { it.endsWith("Rus-Mobile-2.txt") })
        assertTrue(DefaultSources.WHITE.contains("https://gitlab.com/zieng2/wl/-/raw/main/vless_universal.txt"))
        assertTrue(DefaultSources.WHITE.none { it == "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt" })
        assertTrue(DefaultSources.isLowTrust("https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/ru-sni/vless_ru.txt"))
        assertTrue(DefaultSources.isLowTrust("https://cdn.jsdelivr.net/gh/indelingDanil/MyAppVPN-subs@output/vless.txt"))
    }
}
