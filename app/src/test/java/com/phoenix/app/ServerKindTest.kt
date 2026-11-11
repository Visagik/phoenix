package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerKindTest {
    @Test fun detectsAuto() {
        assertTrue(ServerKind.isAuto("Auto 3 → [🚀 Оптимальная]"))
        assertTrue(ServerKind.isAuto("Auto → [🚀 Оптимальная]"))
        assertFalse(ServerKind.isAuto("Germany 🇩🇪 | [DE] TG:ok"))
        assertFalse(ServerKind.isAuto("Autobahn DE"))
    }

    @Test fun concreteGoFirstKeepingOrder() {
        fun c(n: String) = Candidate("trojan://p@$n:443#$n", n, 3, -1.0, 0, alive = true)
        val out = ServerKind.concreteFirst(listOf(c("Auto 1"), c("DE"), c("Auto 2"), c("NL"))) { it.identity }
        assertEquals(listOf("DE", "NL", "Auto 1", "Auto 2"), out.map { it.identity })
    }
}
