package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreHandlerPickTest {
    @Test fun missingClassFallsBackToNull() {
        assertNull(CoreHandlerPick.create("com.phoenix.app.xray.NoSuchClass"))
        assertNull(CoreHandlerPick.create(CoreHandlerPick.CALLBACK_CLASS)) // в тестах aar нет — класса нет, исключения нет
    }
    @Test fun missingConstructorFallsBackToNull() {
        assertNull(CoreHandlerPick.create("java.lang.StringBuilder", listOf(Int::class.java, Int::class.java), listOf(1, 2)))
    }
    @Test fun existingClassIsCreated() {
        assertEquals("", CoreHandlerPick.create("java.lang.StringBuilder").toString())
        assertEquals("ab", CoreHandlerPick.create("java.lang.StringBuilder", listOf(String::class.java), listOf("ab")).toString())
    }
}
