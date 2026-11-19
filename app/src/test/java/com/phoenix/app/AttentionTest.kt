package com.phoenix.app

import com.phoenix.app.Attention.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionTest {
    private fun env(battery: Boolean = true, notifRel: Boolean = true, notif: Boolean = true, exactRel: Boolean = true, exact: Boolean = true) =
        Onboarding.Env(true, notifRel, notif, battery, exactRel, exact, false, false, true)

    @Test fun allGoodIsEmpty() {
        assertEquals(emptyList<Item>(), Attention.items(env(), false))
    }

    @Test fun revokedRightsAreListedInOrder() {
        assertEquals(listOf(Item.BACKGROUND, Item.NOTIFICATIONS, Item.ALARMS), Attention.items(env(battery = false, notif = false, exact = false), false))
    }

    @Test fun irrelevantRightsAreNotComplained() {
        assertEquals(emptyList<Item>(), Attention.items(env(notifRel = false, notif = false, exactRel = false, exact = false), false))
    }

    @Test fun updateIsAddedLastAndNotCountedAsBackground() {
        val list = Attention.items(env(battery = false), true)
        assertEquals(listOf(Item.BACKGROUND, Item.UPDATE), list)
        assertEquals(1, Attention.backgroundCount(list))
    }
}
