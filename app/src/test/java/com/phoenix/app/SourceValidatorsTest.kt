package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceValidatorsTest {
    @Test fun headersOnlyWhenCacheAndValidatorsExist() {
        val v = SourceValidators.V("\"abc\"", "Wed, 21 Oct 2026 07:28:00 GMT")
        assertEquals(mapOf("If-None-Match" to "\"abc\"", "If-Modified-Since" to "Wed, 21 Oct 2026 07:28:00 GMT"), SourceValidators.requestHeaders(v, true))
        assertTrue(SourceValidators.requestHeaders(v, false).isEmpty())
        assertTrue(SourceValidators.requestHeaders(null, true).isEmpty())
        assertEquals(mapOf("If-None-Match" to "\"abc\""), SourceValidators.requestHeaders(SourceValidators.V("\"abc\"", ""), true))
    }

    @Test fun persistsAndDropsEmpty() {
        val f = File.createTempFile("val", ".tsv")
        val s = SourceValidators(f)
        s.put("https://a/x", "\"e1\"", "")
        s.put("https://b/y", "", "Mon")
        s.put("https://c/z", "", "")
        s.save()
        val r = SourceValidators(f)
        assertEquals("\"e1\"", r.get("https://a/x")!!.etag)
        assertEquals("Mon", r.get("https://b/y")!!.lastModified)
        assertTrue(r.get("https://c/z") == null)
        s.put("https://a/x", "", "")
        assertTrue(s.get("https://a/x") == null)
        f.delete()
    }
}
