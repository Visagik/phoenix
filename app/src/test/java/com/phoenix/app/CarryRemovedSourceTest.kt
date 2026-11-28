package com.phoenix.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сценарий (CHANGELOG 3.9.66, kasesm): источник убрали из списка, но его конфиги ещё несколько дней шли в пул через перенос.
 * Теперь конфиги источников, которых в списке больше нет, не переносятся; конфиги живых источников (даже если те сегодня не ответили) переносятся.
 */
class CarryRemovedSourceTest {
    private val today = LocalDate.of(2026, 11, 25)
    private val key: (String) -> String = { it.substringAfter("://").substringBefore('@') }
    private fun link(n: Int) = "vless://k$n@h:1#n$n"

    @Test fun configsOfRemovedSourceAreNotCarried() {
        val owners = mapOf("k1" to "https://alive/s", "k2" to "https://removed/s")
        val listed = setOf("https://alive/s")
        val out = CarryOver.select(listOf(link(1), link(2)), emptySet(), emptyMap(), today, 3, key, sourceAlive = { k -> owners[k]?.let { it in listed } ?: true }) { true }
        assertEquals(listOf(link(1)), out)
    }

    @Test fun configsOfListedSourceThatFailedTodayAreStillCarried() {
        val owners = mapOf("k1" to "https://failed-today/s")
        val out = CarryOver.select(listOf(link(1)), emptySet(), emptyMap(), today, 3, key, sourceAlive = { k -> owners[k]?.let { it in setOf("https://failed-today/s") } ?: true }) { true }
        assertEquals(listOf(link(1)), out)
    }

    @Test fun unknownOwnerStaysCarriedSoThePoolSurvivesTheUpdate() {
        val out = CarryOver.select(listOf(link(1)), emptySet(), emptyMap(), today, 3, key, sourceAlive = { k -> emptyMap<String, String>()[k]?.let { false } ?: true }) { true }
        assertEquals(listOf(link(1)), out)
    }

    @Test fun ownersFileRoundTrips() {
        val m = linkedMapOf("k1" to "https://a/s", "k2" to "https://b/s?x=1&y=2")
        assertEquals(m, CarryOver.decodeOwners(CarryOver.encodeOwners(m)))
        assertTrue(CarryOver.decodeOwners("мусор без табуляции\n\tпусто\nk\t\n").isEmpty())
    }

    @Test fun nextOwnersKeepsFreshAndCarriedButNotInventedOnes() {
        val next = CarryOver.nextOwners(mapOf("k1" to "https://a"), mapOf("k1" to "https://old", "k2" to "https://b", "k9" to "https://c"), setOf("k2", "k3"))
        assertEquals(mapOf("k1" to "https://a", "k2" to "https://b"), next)
    }

    @Test fun sourceTagReportsOwnerOfEveryConfigIncludingTaggedDuplicates() {
        val k: (String) -> String = { it.substringAfter("://").substringBefore('@') + it.substringAfter('#').substringBefore("%20(") }
        val owners = HashMap<String, String>()
        val out = SourceTag.apply(
            listOf("https://a/s" to listOf("vless://x1@h:1#Germany"), "https://b/s" to listOf("vless://x2@h:1#Germany", "vless://x3@h:1#Poland")), k, owners,
        )
        assertEquals(3, out.size)
        assertEquals(3, owners.size)
        assertEquals("https://b/s", owners[k(out.first { it.contains("x3@") })])
        assertEquals("https://a/s", owners[k(out.first { it.contains("x1@") })])
        assertTrue(owners.values.toSet() == setOf("https://a/s", "https://b/s"))
    }
}
