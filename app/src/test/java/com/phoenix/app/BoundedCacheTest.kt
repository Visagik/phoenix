package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedCacheTest {
    @Test fun evictsOldestOneNotAll() {
        val c = BoundedCache<Int, String>(3)
        c.put(1, "a"); c.put(2, "b"); c.put(3, "c")
        c.put(4, "d")
        assertEquals(3, c.size())
        assertNull(c.get(1))
        assertEquals("d", c.get(4))
        assertEquals("b", c.get(2))
    }

    @Test fun readRefreshesEntry() {
        val c = BoundedCache<Int, String>(2)
        c.put(1, "a"); c.put(2, "b")
        c.get(1)
        c.put(3, "c")
        assertEquals("a", c.get(1))
        assertNull(c.get(2))
    }
}
