package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class GeoIpTest {
    private fun db(): GeoIpDb {
        val out = ByteArrayOutputStream()
        GeoIpDb.write(out, listOf(
            Triple(GeoIpDb.parseIpv4("1.0.0.0")!!, GeoIpDb.parseIpv4("1.0.0.255")!!, "AU"),
            Triple(GeoIpDb.parseIpv4("77.88.0.0")!!, GeoIpDb.parseIpv4("77.88.255.255")!!, "RU"),
            Triple(GeoIpDb.parseIpv4("200.0.0.0")!!, GeoIpDb.parseIpv4("201.255.255.255")!!, "BR"), // старший бит адреса — не знак
        ))
        return GeoIpDb.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun lookupFindsRangesIncludingHighAddresses() {
        val d = db()
        assertEquals("AU", d.lookup("1.0.0.77"))
        assertEquals("RU", d.lookup("77.88.8.8"))
        assertEquals("BR", d.lookup("200.10.10.10"))
        assertEquals("BR", d.lookup("201.255.255.255"))
        assertNull(d.lookup("8.8.8.8"))
        assertNull(d.lookup("1.0.1.0"))
        assertNull(d.lookup("not-an-ip"))
        assertNull(d.lookup("256.1.1.1"))
    }

    @Test
    fun bundledTableKnowsWellKnownAddresses() {
        val f = listOf(File("src/main/assets/geoip4.bin"), File("app/src/main/assets/geoip4.bin"), File("/home/claude/phoenix-android/app/src/main/assets/geoip4.bin")).firstOrNull { it.exists() } ?: return
        val d = GeoIpDb.read(f.inputStream())
        assertTrue(d.size > 100_000)
        assertEquals("US", d.lookup("8.8.8.8"))
        assertEquals("RU", d.lookup("77.88.8.8"))
        assertEquals("AU", d.lookup("1.1.1.1"))
        assertEquals("DE", d.lookup("5.9.0.1"))
    }
}
