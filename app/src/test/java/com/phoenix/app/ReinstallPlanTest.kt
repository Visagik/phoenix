package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReinstallPlanTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun signingLineIsFoundInNotes() {
        assertEquals(a, UpdateCheck.signingFromNotes("Phoenix 3.9.68\nПодпись: $a\nЧто нового"))
        assertEquals(a, UpdateCheck.signingFromNotes("signing: sha256 " + a.uppercase()))
        val colon = (0 until 32).joinToString(":") { "ab" }
        assertEquals("ab".repeat(32), UpdateCheck.signingFromNotes("Signature = $colon"))
        assertEquals("", UpdateCheck.signingFromNotes("sha256 контрольная сумма файла: $a"))
        assertEquals("", UpdateCheck.signingFromNotes("Подпись: не указана"))
    }

    @Test
    fun releaseInfoCarriesSigning() {
        val json = """{"tag_name":"v3.9.68","html_url":"https://github.com/Visagik/phoenix/releases/tag/v3.9.68","body":"Подпись: $a\nпрочее","assets":[]}"""
        assertEquals(a, UpdateCheck.parse(json)!!.signingSha256)
    }

    @Test
    fun reinstallDecidedByRelease() {
        assertTrue(ReinstallPlan.neededByRelease(setOf(a), b))
        assertFalse(ReinstallPlan.neededByRelease(setOf(a), a.uppercase()))
        assertFalse(ReinstallPlan.neededByRelease(setOf(a), "")) // в описании не указано — не гадаем
        assertFalse(ReinstallPlan.neededByRelease(emptySet(), b)) // свой ключ не определился
    }

    @Test
    fun reinstallDecidedByFile() {
        assertTrue(ReinstallPlan.neededByFile(setOf(a), setOf(b)))
        assertFalse(ReinstallPlan.neededByFile(setOf(a, b), setOf(b)))
        assertFalse(ReinstallPlan.neededByFile(setOf(a), emptySet()))
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("Phoenix-v3.9.68.apk", ReinstallPlan.apkFileName("v3.9.68"))
        assertEquals("Phoenix-v3.9.68.apk", ReinstallPlan.apkFileName("v3.9.68/../"))
        assertEquals("Phoenix-new.apk", ReinstallPlan.apkFileName("///"))
    }
}
