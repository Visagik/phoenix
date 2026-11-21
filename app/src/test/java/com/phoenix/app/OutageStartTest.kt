package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OutageStartTest {
    @Test fun usesFirstFailureWhenRecent() {
        assertEquals(1_000L, OutageStart.pick(18_000L, 1_000L)) // сбой 17 с назад
    }
    @Test fun ignoresMissingOrStaleFailure() {
        assertEquals(500_000L, OutageStart.pick(500_000L, 0L))
        assertEquals(1_000_000L, OutageStart.pick(1_000_000L, 1_000L)) // сбой давно: другой эпизод
        assertEquals(1_000L, OutageStart.pick(1_000L, 5_000L)) // сбой «в будущем»: игнорируем
    }
}
