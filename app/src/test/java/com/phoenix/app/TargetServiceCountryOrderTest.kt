package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сценарий: сервисы из целей (id «APP:пакет») тоже учитывают страну выхода.
 * Раньше таблица стран искала сервис по id среди TG/QW/CL…, получала −1, и сервер из закрытой страны без замера не уходил в конец.
 */
class TargetServiceCountryOrderTest {
    private val claudeTarget = CheckService("APP:com.anthropic.claude", "Claude", "https://claude.ai/", "CL", legacyId = "CL")
    private val customTarget = CheckService("SITE:example.org", "Example", "https://example.org/", "EX")

    private fun cand(cc: String) = Candidate(link = "vless://x@h:1", identity = "x", days = 1, mbps = 1.0, latencyMs = 1, cc = cc)

    @Test fun targetServiceFindsItsColumnThroughLegacyId() {
        assertEquals(2, ServiceBlocks.index(claudeTarget))
        assertEquals(2, ServiceBlocks.index(CheckServices.ALL.first { it.id == "CL" }))
    }

    @Test fun serviceWithoutBuiltInTwinIsNotInTheTable() {
        assertEquals(-1, ServiceBlocks.index(customTarget))
    }

    @Test fun unmeasuredServerFromClosedCountryGoesLastForTargetService() {
        assertEquals(4, ServiceMatch.groupOf(cand("RU"), emptyMap(), claudeTarget))
        assertEquals(2, ServiceMatch.groupOf(cand("DE"), emptyMap(), claudeTarget))
    }

    @Test fun customTargetIsNotAffectedByCountryTable() {
        assertEquals(2, ServiceMatch.groupOf(cand("RU"), emptyMap(), customTarget))
    }

    @Test fun exitBlockedWorksForTargetService() {
        assertTrue(AppFit.exitBlocked("https://claude.ai/", claudeTarget, "RU"))
        assertFalse(AppFit.exitBlocked("https://claude.ai/", claudeTarget, "DE"))
        assertFalse(AppFit.exitBlocked("https://example.org/", customTarget, "RU"))
    }
}
