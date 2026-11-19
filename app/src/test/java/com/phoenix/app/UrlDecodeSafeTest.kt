package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlDecodeSafeTest {
    @Test fun brokenPercentStaysAsIs() {
        assertEquals("100% скорость", urlDecode("100% скорость"))
        assertEquals("a%2", urlDecode("a%2"))
        assertEquals("%", urlDecode("%"))
        assertEquals("%zz", urlDecode("%zz"))
    }

    @Test fun normalDecoding() {
        assertEquals("П", urlDecode("%D0%9F"))
        assertEquals("Привет мир", urlDecode("%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82+%D0%BC%D0%B8%D1%80"))
        assertEquals("a b", urlDecode("a%20b"))
        assertEquals("plain", urlDecode("plain"))
    }

    @Test fun partlyBrokenDecodesGoodParts() {
        assertEquals("a b 100%", urlDecode("a%20b%20100%"))
    }
}
