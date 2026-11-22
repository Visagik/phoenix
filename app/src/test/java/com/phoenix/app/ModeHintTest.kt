package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeHintTest {
    @Test fun noteCountsSameModeAndResetsOnOther() {
        var m: Map<String, ModeHint.Entry> = emptyMap()
        m = ModeHint.note(m, "mobile:мтс", Mode.WHITELIST)
        assertEquals(1, m["mobile:мтс"]!!.count)
        m = ModeHint.note(m, "mobile:мтс", Mode.WHITELIST)
        assertEquals(2, m["mobile:мтс"]!!.count)
        m = ModeHint.note(m, "mobile:мтс", Mode.BLACKLIST)
        assertEquals(ModeHint.Entry(Mode.BLACKLIST, 1), m["mobile:мтс"])
    }

    @Test fun guessNeedsConfirmationsAndDifferentMode() {
        var m: Map<String, ModeHint.Entry> = emptyMap()
        m = ModeHint.note(m, "wifi", Mode.BLACKLIST)
        assertNull(ModeHint.guess(m, "wifi", Mode.WHITELIST)) // одного подтверждения мало
        m = ModeHint.note(m, "wifi", Mode.BLACKLIST)
        assertEquals(Mode.BLACKLIST, ModeHint.guess(m, "wifi", Mode.WHITELIST))
        assertNull(ModeHint.guess(m, "wifi", Mode.BLACKLIST)) // уже тот же режим
        assertNull(ModeHint.guess(m, "mobile:мтс", Mode.WHITELIST)) // другая сеть
    }

    @Test fun noneIsNeverRemembered() {
        assertTrue(ModeHint.note(emptyMap(), "none", Mode.WHITELIST).isEmpty())
        assertTrue(ModeHint.note(emptyMap(), "", Mode.WHITELIST).isEmpty())
    }

    @Test fun parseRenderRoundTripAndGarbage() {
        val m = ModeHint.note(ModeHint.note(emptyMap(), "wifi", Mode.BLACKLIST), "mobile:мтс", Mode.WHITELIST)
        assertEquals(m, ModeHint.parse(ModeHint.render(m)))
        assertTrue(ModeHint.parse("мусор\nwifi\tXXX\t3\nwifi\tBLACKLIST\tx\n").isEmpty())
    }

    @Test fun capsNetCount() {
        var m: Map<String, ModeHint.Entry> = emptyMap()
        for (i in 1..40) m = ModeHint.note(m, "mobile:op$i", Mode.WHITELIST)
        assertEquals(ModeHint.MAX_NETS, m.size)
        assertFalse(m.containsKey("mobile:op1"))
    }

    @Test fun staleWhenNetworkChangedDuringMeasure() {
        assertTrue(ModeHint.stale("wifi", "mobile:мтс"))
        assertFalse(ModeHint.stale("wifi", "wifi"))
    }

    @Test fun provisionalRevertsOnlyOnOtherNetAndOnlyOnce() {
        val p = ProvisionalMode()
        assertNull(p.revertOnNetChange("wifi"))
        p.start(Mode.WHITELIST, "wifi")
        assertTrue(p.active)
        assertNull(p.revertOnNetChange("wifi")) // та же сеть — держим
        assertEquals(Mode.WHITELIST, p.revertOnNetChange("mobile:мтс"))
        assertFalse(p.active)
        assertNull(p.revertOnNetChange("cable"))
    }

    @Test fun provisionalConfirmedIsNotReverted() {
        val p = ProvisionalMode()
        p.start(Mode.WHITELIST, "wifi")
        p.confirm()
        assertNull(p.revertOnNetChange("mobile:мтс"))
    }
}
