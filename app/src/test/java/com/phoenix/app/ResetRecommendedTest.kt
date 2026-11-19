package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetRecommendedTest {
    @Test fun commonScopeResetsScopedAndSelectedCommonKeys() {
        val keys = SettingsKeys.recommendedResetKeys(null)
        assertTrue("kill_switch" in keys)
        assertTrue("block_quic" in keys)
        assertTrue("pin_hours" in keys)
        assertTrue("apps_mode" in keys)
    }

    @Test fun modeScopeUsesModePrefixedKeys() {
        val keys = SettingsKeys.recommendedResetKeys("WHITELIST")
        assertTrue("m_WHITELIST_kill_switch" in keys)
        assertFalse("kill_switch" in keys)
    }

    @Test fun personalAndScheduleSettingsAreNeverReset() {
        val keys = SettingsKeys.recommendedResetKeys(null).toSet()
        for (k in listOf("language", "theme", "animations", "run_hour", "run_minute", "schedule_kind", "interval_hours", "quiet_from", "quiet_to", "only_apps", "auto_backup")) {
            assertFalse(k, k in keys)
        }
    }

    @Test fun resetListHasNoDuplicates() {
        val keys = SettingsKeys.recommendedResetKeys(null)
        assertEquals(keys.size, keys.toSet().size)
    }
}
