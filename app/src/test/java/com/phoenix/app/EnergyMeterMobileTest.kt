package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyMeterMobileTest {
    @Test fun countsMobileBytesOnlyOnMobile() {
        val f = java.io.File.createTempFile("energy", ".tsv")
        try {
            val m = EnergyMeter(f)
            NetHooks.mobile = { false }
            m.add("speed", 2, "2026-11-19", 0)
            assertEquals(0L, m.mobileBytes("2026-11-19"))
            NetHooks.mobile = { true }
            m.add("speed", 3, "2026-11-19", 0)
            assertEquals(3 * EnergyMeter.SPEED_BYTES, m.mobileBytes("2026-11-19"))
            assertEquals(5 * EnergyMeter.SPEED_BYTES, m.day("2026-11-19").bytes)
            m.save(Long.MAX_VALUE / 2)
            val again = EnergyMeter(f)
            assertEquals(3 * EnergyMeter.SPEED_BYTES, again.mobileBytes("2026-11-19"))
        } finally {
            NetHooks.mobile = { false }
            f.delete()
        }
    }
}
