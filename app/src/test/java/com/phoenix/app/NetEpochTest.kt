package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetEpochTest {
    @Test fun factIsVisibleOnlyInItsEpoch() {
        var ep = 1L
        val f = EpochFact(0L) { ep }
        assertEquals(0L, f.value)
        f.value = 500L
        assertEquals(500L, f.value)
        ep = 2L // сеть сменилась
        assertEquals(0L, f.value)
        f.value = 700L
        assertEquals(700L, f.value)
    }

    @Test fun nullableFactResets() {
        var ep = 1L
        val f = EpochFact<String?>(null) { ep }
        f.value = "white"
        ep = 2L
        assertNull(f.value)
    }

    @Test fun bumpAdvances() {
        val a = NetEpoch.id
        assertEquals(a + 1, NetEpoch.bump())
        assertEquals(a + 1, NetEpoch.id)
    }

    @Test fun offlineFromOldNetworkDoesNotBlockNewOne() {
        var ep = 1L
        val offlineAt = EpochFact(0L) { ep }
        offlineAt.value = 1_000_000L // Wi-Fi без интернета
        assertEquals(true, FailoverGate.offlineRecently(offlineAt.value, 1_010_000L))
        ep = 2L // ушли на мобильную
        assertEquals(false, FailoverGate.offlineRecently(offlineAt.value, 1_020_000L))
    }
}
