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
        // Н6: настройки других экранов не трогаются
        for (k in listOf("apps_mode", "auto_mode", "only_charging", "bg_wifi_only", "bg_mobile_mb", "auto_service_check", "global_policy")) assertFalse(k, k in keys)
    }

    @Test fun modeScopeHasOnlyPrefixedKeysAndNoCommon() {
        val keys = SettingsKeys.recommendedResetKeys("WHITELIST")
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.joinToString(), keys.all { it.startsWith("m_WHITELIST_") })
        for (k in SettingsKeys.CONNECTION_SCREEN_COMMON) assertFalse(k, k in keys)
    }

    @Test fun connectionScreenKeysAreScoped() {
        for (k in SettingsKeys.CONNECTION_SCREEN_SCOPED) assertTrue(k, k in SettingsKeys.SCOPED)
        for (k in SettingsKeys.CONNECTION_SCREEN_COMMON) assertTrue(k, k in SettingsKeys.COMMON)
    }

    @Test fun dialogLabelsExistForEveryKey() {
        for (k in SettingsKeys.resetBaseKeys(null)) assertTrue(k, k in SettingsKeys.LABELS)
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
