package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Release3969Test {
    @Test fun noConfigGetsCollectHint() {
        assertNotNull(ErrorHint.key("Нет конфига для подключения: выберите его в списке"))
        assertEquals(true, ErrorHint.key("Нет конфига для подключения: выберите его в списке")!!.contains("Собрать"))
    }

    @Test fun backgroundDenialGetsPermissionHint() {
        assertEquals(true, ErrorHint.key("Система не разрешила запуск VPN из фона — откройте приложение")!!.contains("разрешения"))
    }

    @Test fun startErrorGetsRetryHint() {
        assertNotNull(ErrorHint.key("Ошибка запуска: boom"))
    }

    @Test fun unknownErrorHasNoHint() {
        assertNull(ErrorHint.key("что-то совсем другое"))
        assertNull(ErrorHint.key(""))
    }
}
