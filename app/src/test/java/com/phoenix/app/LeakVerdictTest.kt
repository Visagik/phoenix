package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakVerdictTest {
    @Test fun verdicts() {
        assertEquals(LeakVerdict.Kind.OK, LeakVerdict.of("1.2.3.4", "5.6.7.8"))
        assertEquals(LeakVerdict.Kind.SAME_IP, LeakVerdict.of("1.2.3.4", " 1.2.3.4 "))
        assertEquals(LeakVerdict.Kind.UNKNOWN, LeakVerdict.of(null, "5.6.7.8"))
        assertEquals(LeakVerdict.Kind.UNKNOWN, LeakVerdict.of("1.2.3.4", ""))
    }

    @Test fun parseIp() {
        assertEquals("203.0.113.9", LeakVerdict.parseIp("203.0.113.9\n"))
        assertEquals("2001:db8::1", LeakVerdict.parseIp("2001:db8::1"))
        assertNull(LeakVerdict.parseIp("<html>ошибка</html>"))
    }
}
