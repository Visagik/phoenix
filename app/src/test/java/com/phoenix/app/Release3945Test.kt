package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Release3945Test {
    @Test fun notebookUnsupportedRedirectIsRecognised() {
        // как в журнале: код 200, адрес после переходов с параметром, в тексте — только служебные стили
        assertTrue(RegionBlock.blocked("https://notebook.google/?location=unsupported", ".glue-cookie-notification-bar{box"))
        assertTrue(RegionBlock.blocked("https://x.example/page?region=blocked", ""))
        assertTrue(RegionBlock.blocked("https://x.example/?unsupported_country=1", ""))
    }

    @Test fun ordinaryQueriesAreNotBlocked() {
        assertFalse(RegionBlock.blocked("https://notebooklm.google.com/?hl=ru", "<html>ok</html>"))
        assertFalse(RegionBlock.blocked("https://gemini.google.com/app?q=unsupported+browser", "<html>ok</html>"))
        assertFalse(RegionBlock.blocked("https://x.example/", ""))
    }

    @Test fun retryDelays() {
        assertEquals(10 * 60_000L, Schedule.retryDelayMs(noNetwork = true))
        assertEquals(30 * 60_000L, Schedule.retryDelayMs(noNetwork = false))
    }

    @Test fun retryNeededOnlyWhileDue() {
        val h = Schedule.HOUR_MS
        val now = 100 * h
        assertTrue(Schedule.needsRetry(true, now - 13 * h, now, 12, false))
        assertFalse(Schedule.needsRetry(true, now - 2 * h, now, 12, false))
        assertTrue(Schedule.needsRetry(false, 0, now, 12, false))
        assertFalse(Schedule.needsRetry(false, 0, now, 12, true))
    }
}
