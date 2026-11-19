package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimPolicyTest {
    @Test fun onlyWhenUserOnAndSystemAllows() {
        assertTrue(AnimPolicy.enabled(true, 1f))
        assertTrue(AnimPolicy.enabled(true, 0.5f))
        assertFalse(AnimPolicy.enabled(false, 1f))
        assertFalse(AnimPolicy.enabled(true, 0f))
    }

    @Test fun glowFadesAsItGrows() {
        assertEquals(1.0, AnimPolicy.glowScale(0f).toDouble(), 1e-6)
        assertEquals(1.08, AnimPolicy.glowScale(1f).toDouble(), 1e-6)
        assertEquals(0.35, AnimPolicy.glowAlpha(0f).toDouble(), 1e-6)
        assertEquals(0.0, AnimPolicy.glowAlpha(1f).toDouble(), 1e-6)
    }

    @Test fun phaseIsClamped() {
        assertEquals(1.08, AnimPolicy.glowScale(5f).toDouble(), 1e-6)
        assertEquals(0.35, AnimPolicy.glowAlpha(-3f).toDouble(), 1e-6)
    }
}
