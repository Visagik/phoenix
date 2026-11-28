package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitchTimingTest {
    @Test fun startTextShowsWaitOnlyWhenNoticeable() {
        assertEquals("начинаю (просит: проверка связи, ждал очереди 57 с)", SwitchTiming.startText("проверка связи", 57_400))
        assertEquals("начинаю (просит: проверка связи)", SwitchTiming.startText("проверка связи", 400))
        assertEquals("начинаю (просит: не указано)", SwitchTiming.startText("", 0))
    }

    @Test fun prepLineOnlyFromThreeSeconds() {
        assertNull(SwitchTiming.prepText(2_999, 10))
        assertEquals("подготовка 13 с (конфигов 8219)", SwitchTiming.prepText(13_000, 8219))
    }

    @Test fun textSkipsSmallParts() {
        assertEquals("подготовка 13 с, поиск 51 с, всего 64 с", SwitchTiming.text(13_000, 51_000))
        assertEquals("поиск 20 с, всего 20 с", SwitchTiming.text(300, 20_000))
        assertEquals("всего 0 с", SwitchTiming.text(0, 0))
    }
}
