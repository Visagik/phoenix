package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {
    @Test fun removesBracketsAndEmoji() {
        assertEquals("Auto 5 → Оптимальная", DisplayName.plain("Auto 5 → [🚀 Оптимальная]"))
    }

    @Test fun removesFlags() {
        assertEquals("Latvia 3", DisplayName.plain("🇱🇻 Latvia 3"))
    }

    @Test fun keepsPlainText() {
        assertEquals("Server-1 (fast)", DisplayName.plain("Server-1 (fast)"))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", DisplayName.plain("  [ ]  "))
    }
}
