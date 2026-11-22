package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpareServerTest {
    @Test
    fun spareIsKeptPerNetworkAndMode() {
        val now = 1_700_000_000_000L
        SpareServer.forget()
        SpareServer.remember("vless://a", "idA", "wifi", now, "BLACK")
        assertNull(SpareServer.take("wifi", now + 1000, "other", "WHITE")) // другой режим
        SpareServer.remember("vless://a", "idA", "wifi", now, "BLACK")
        assertNull(SpareServer.take("mobile", now + 1000, "other", "BLACK")) // другая сеть
        SpareServer.remember("vless://a", "idA", "wifi", now, "BLACK")
        assertEquals("vless://a", SpareServer.take("wifi", now + 1000, "other", "BLACK"))
        assertNull(SpareServer.take("wifi", now + 2000, "other", "BLACK")) // уже взят
    }
}
