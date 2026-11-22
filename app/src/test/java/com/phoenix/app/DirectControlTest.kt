package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectControlTest {
    @Test
    fun picksTrustedNotAlreadyChecked() {
        val all = listOf("sber", "vk", "ok", "yandex", "ozon")
        assertEquals(listOf("vk", "yandex"), DirectControl.pick(all, setOf("sber", "ok"), setOf("vk", "yandex", "ozon", "ok"), 2))
        assertEquals(emptyList<String>(), DirectControl.pick(all, setOf("sber"), emptySet()))
    }

    @Test
    fun blockedOnlyWithOpenControlIsNotNoNetwork() {
        // все «пора» закрыты, а контрольный открылся — сеть есть
        assertFalse(DirectControl.noNetwork(anyOpened = false, controlsTried = true, controlOpened = true))
    }

    @Test
    fun noNetworkWhenControlAlsoFailsOrMissing() {
        assertTrue(DirectControl.noNetwork(anyOpened = false, controlsTried = true, controlOpened = false))
        assertTrue(DirectControl.noNetwork(anyOpened = false, controlsTried = false, controlOpened = false))
        assertFalse(DirectControl.noNetwork(anyOpened = true, controlsTried = false, controlOpened = false))
    }
}
