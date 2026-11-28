package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeHintTest {
    @Test fun noteCountsConfirmedUpAndOtherDown() {
        var m: Map<String, ModeHint.Entry> = emptyMap()
        m = ModeHint.note(m, "mobile:мтс", Mode.WHITELIST)
        assertEquals(ModeHint.Entry(1, 0), m["mobile:мтс"])
        m = ModeHint.note(m, "mobile:мтс", Mode.WHITELIST)
        assertEquals(ModeHint.Entry(2, 0), m["mobile:мтс"])
        m = ModeHint.note(m, "mobile:мтс", Mode.BLACKLIST)
        assertEquals(ModeHint.Entry(1, 1), m["mobile:мтс"])
        m = ModeHint.note(ModeHint.note(m, "mobile:мтс", Mode.BLACKLIST), "mobile:мтс", Mode.BLACKLIST)
        assertEquals(ModeHint.Entry(0, 3), m["mobile:мтс"])
    }

    @Test fun oldFormatIsReadAndNewFormatRoundTrips() {
        val m = ModeHint.parse("mobile:megafon\tWHITELIST\t50\nwifi\tBLACKLIST\t7\n")
        assertEquals(ModeHint.Entry(50, 0), m["mobile:megafon"])
        assertEquals(ModeHint.Entry(0, 7), m["wifi"])
        assertEquals("mobile:megafon\tWL\t50\tBL\t0\nwifi\tWL\t0\tBL\t7\n", ModeHint.render(m))
        assertEquals(m, ModeHint.parse(ModeHint.render(m)))
    }

    @Test fun day28Nov_threeBlacklistConfirmationsKeepUsualWhitelist() {
        var m: Map<String, ModeHint.Entry> = mapOf("mobile:megafon" to ModeHint.Entry(50, 0))
        repeat(3) { m = ModeHint.note(m, "mobile:megafon", Mode.BLACKLIST) }
        assertEquals(ModeHint.Entry(47, 3), m["mobile:megafon"])
        assertEquals(Mode.WHITELIST, ModeHint.usual(m, "mobile:megafon"))
        assertEquals(Mode.WHITELIST, ModeHint.guess(m, "mobile:megafon", Mode.BLACKLIST))
    }

    @Test fun usualNeedsDoubleAdvantage() {
        var m: Map<String, ModeHint.Entry> = mapOf("n" to ModeHint.Entry(50, 0))
        repeat(34) { m = ModeHint.note(m, "n", Mode.BLACKLIST) }
        assertEquals(ModeHint.Entry(16, 34), m["n"])
        assertEquals(Mode.BLACKLIST, ModeHint.usual(m, "n"))
        assertNull(ModeHint.usual(mapOf("n" to ModeHint.Entry(25, 25)), "n"))
        assertNull(ModeHint.usual(mapOf("n" to ModeHint.Entry(1, 0)), "n")) // меньше NEED
        assertNull(ModeHint.usual(emptyMap(), "n"))
    }

    @Test fun countsStayInRange() {
        var m: Map<String, ModeHint.Entry> = emptyMap()
        repeat(80) { m = ModeHint.note(m, "n", Mode.WHITELIST) }
        assertEquals(ModeHint.Entry(50, 0), m["n"])
        repeat(80) { m = ModeHint.note(m, "n", Mode.BLACKLIST) }
        assertEquals(ModeHint.Entry(0, 50), m["n"])
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
