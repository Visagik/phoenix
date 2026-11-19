package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyToneSubTest {
    @Test fun explicitSubWinsOverText() {
        // текст на любом языке: решает явное уточнение службы
        assertEquals(NotifyTone.RED, NotifyTone.of(true, false, "no connection through this server", VpnSub.BROKEN))
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(true, false, "the phone has no network", VpnSub.NO_NETWORK))
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(true, false, "looking for a server", VpnSub.SEARCHING))
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(false, true, "", VpnSub.SEARCHING))
    }

    @Test fun noSubKeepsOldRules() {
        assertEquals(NotifyTone.GREEN, NotifyTone.of(true, false, ""))
        assertEquals(NotifyTone.YELLOW, NotifyTone.of(false, true, ""))
        assertEquals(NotifyTone.RED, NotifyTone.of(true, false, "нет связи через этот сервер"))
    }
}
