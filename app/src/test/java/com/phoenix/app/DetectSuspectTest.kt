package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectSuspectTest {
    private fun c(f: Int, r: Int) = ProbeCounts(f, 11, r, 4)

    @Test fun russianAllClosedIsSuspect() {
        assertTrue(BlockDetector.suspect(c(0, 0)))
        assertTrue(BlockDetector.suspect(c(1, 0)))
        assertTrue(BlockDetector.suspect(c(2, 0)))
    }

    @Test fun realBlockingIsNotSuspect() {
        assertFalse(BlockDetector.suspect(c(10, 3))) // чёрные списки
        assertFalse(BlockDetector.suspect(c(0, 3)))  // белые списки: зарубежные закрыты, российские открыты
        assertFalse(BlockDetector.suspect(ProbeCounts(0, 0, 0, 0))) // нет данных — не подозрение
    }

    @Test fun betterTakesMoreOpened() {
        val a = c(1, 0); val b = c(10, 3)
        assertEquals(b, BlockDetector.better(a, b))
        assertEquals(b, BlockDetector.better(b, a))
        assertEquals(a, BlockDetector.better(a, c(0, 0))) // повтор хуже — остаётся первый
    }

    @Test fun retryTurnsFalseOfflineIntoBlacklist() {
        val first = c(0, 0)
        assertEquals(BlockType.OFFLINE, BlockDetector.classify(first))
        assertEquals(BlockType.BLACKLIST, BlockDetector.classify(BlockDetector.better(first, c(10, 3))))
    }
}
