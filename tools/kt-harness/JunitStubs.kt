package org.junit
annotation class Test
object Assert {
    fun assertEquals(a: Any?, b: Any?) { if (a != b) throw AssertionError("expected <$a> but was <$b>") }
    fun assertTrue(c: Boolean) { if (!c) throw AssertionError("expected true") }
    fun assertTrue(m: String, c: Boolean) { if (!c) throw AssertionError(m) }
    fun assertFalse(c: Boolean) { if (c) throw AssertionError("expected false") }
    fun assertFalse(m: String, c: Boolean) { if (c) throw AssertionError(m) }
    fun assertNull(a: Any?) { if (a != null) throw AssertionError("expected null but was <$a>") }
    fun assertNotNull(a: Any?) { if (a == null) throw AssertionError("expected not null") }
    fun assertEquals(a: Double, b: Double, delta: Double) { if (kotlin.math.abs(a - b) > delta) throw AssertionError("expected <$a> but was <$b> (delta $delta)") }
    fun assertEquals(m: String, a: Any?, b: Any?) { if (a != b) throw AssertionError("$m: expected <$a> but was <$b>") }
}
