package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3927Test {
    @Test fun spareTakenOnceInSameNet() {
        SpareServer.forget()
        SpareServer.remember("L", "id2", "wifi:a", 1_000L)
        assertEquals("L", SpareServer.take("wifi:a", 2_000L, "id1"))
        assertNull(SpareServer.take("wifi:a", 2_000L, "id1"))
    }

    @Test fun spareRejectedWhenStaleOtherNetOrCurrent() {
        SpareServer.remember("L", "id2", "wifi:a", 1_000L)
        assertNull(SpareServer.take("mobile", 2_000L, "id1"))
        SpareServer.remember("L", "id2", "wifi:a", 1_000L)
        assertNull(SpareServer.take("wifi:a", 1_000L + SpareServer.MAX_AGE_MS + 1, "id1"))
        SpareServer.remember("L", "id2", "wifi:a", 1_000L)
        assertNull(SpareServer.take("wifi:a", 2_000L, "id2"))
        SpareServer.forget()
    }

    @Test fun hotPhoneDefersHeavyUnlessManual() {
        assertFalse(HeavyGate.allow(false, true, 90, false, 0, hot = true))
        assertTrue(HeavyGate.allow(true, false, 10, true, 0, hot = true))
        assertTrue(HeavyGate.allow(false, true, 90, false, 0, hot = false))
    }

    @Test fun spareFromQuarantineIsNotRememberedOrTaken() {
        Quarantine.clear()
        SpareServer.forget()
        // сервер только что не дал связи через туннель — его не запоминаем в запас
        Quarantine.add("quarantined", now = 1_000L)
        SpareServer.remember("L", "quarantined", "wifi:a", 1_000L)
        assertNull(SpareServer.take("wifi:a", 1_500L, "id1"))
        // обычный, не в карантине — запоминается и берётся как раньше
        SpareServer.remember("L", "clean", "wifi:a", 1_000L)
        assertEquals("L", SpareServer.take("wifi:a", 1_500L, "id1"))
        // запомнили чистым, а в карантин он попал уже после — take тоже отказывает
        SpareServer.remember("L", "clean", "wifi:a", 1_000L)
        Quarantine.add("clean", now = 1_200L)
        assertNull(SpareServer.take("wifi:a", 1_500L, "id1"))
        Quarantine.clear()
        SpareServer.forget()
    }
}
