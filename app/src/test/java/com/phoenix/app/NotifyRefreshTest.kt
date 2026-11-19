package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyRefreshTest {
    @Test
    fun textChangeRefreshesEvenWithSameColor() {
        assertTrue(NotifyTone.needsRefresh(NotifyTone.GREEN, NotifyTone.GREEN, "разбираюсь, что не работает…", "всё работает"))
        assertFalse(NotifyTone.needsRefresh(NotifyTone.GREEN, NotifyTone.GREEN, "всё работает", "всё работает"))
        assertFalse(NotifyTone.needsRefresh(NotifyTone.GREEN, NotifyTone.GREEN, "", "всё работает"))
        assertTrue(NotifyTone.needsRefresh(NotifyTone.YELLOW, NotifyTone.GREEN, "всё работает", "всё работает"))
    }

    @Test
    fun fixingNoteIsYellowAtOnce() {
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(true, false, "разбираюсь, что не работает…"))
    }
}
