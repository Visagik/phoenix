package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3968Test {
    private val link = "vless://11111111-2222-3333-4444-555555555555@example.org:443?security=reality#Germany%201"

    @Test fun cachedParseEqualsUncached() {
        val a = LinkParser.parse(link)
        assertNotNull(a)
        assertEquals(LinkParser.parseUncached(link), a)
        assertTrue(a === LinkParser.parse(link)) // второй раз — из кэша
        assertEquals("example.org:443", a!!.address)
    }

    @Test fun invalidLinkIsCachedAsNull() {
        assertNull(LinkParser.parse("not a link"))
        assertNull(LinkParser.parse("not a link"))
    }

    @Test fun keyOfIsStableWithCache() {
        val k1 = CheckStore.keyOf(link)
        val k2 = CheckStore.keyOf(link)
        assertEquals(k1, k2)
        assertEquals(12, k1.length)
    }

    @Test fun geoIpLoadsOnce() {
        GeoIp.reset()
        var opened = 0
        val out = java.io.ByteArrayOutputStream()
        GeoIpDb.write(out, listOf(Triple(16777216L, 16777471L, "AU")))
        val bytes = out.toByteArray()
        GeoIp.source = { opened++; java.io.ByteArrayInputStream(bytes) }
        val a = GeoIp.load()
        val b = GeoIp.load()
        assertNotNull(a)
        assertTrue(a === b)
        assertEquals(1, opened)
        assertEquals("AU", a!!.lookup("1.0.0.5"))
        GeoIp.reset(); GeoIp.source = null
    }
}
