package io.github.nasmonitor.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTabOrderTest {
    private val defaults = listOf("overview", "performance", "logs", "channels", "usage")

    @Test
    fun defaultOrderIsStable() {
        assertEquals(defaults, gatewayDefaultOrder())
        assertEquals(defaults, normalizeGatewayOrder(null))
        assertEquals(defaults, normalizeGatewayOrder(emptyList()))
    }

    @Test
    fun savedOrderIsRestoredWithoutLoss() {
        val saved = listOf("logs", "overview", "usage", "performance", "channels")
        assertEquals(saved, normalizeGatewayOrder(saved))
    }

    @Test
    fun unknownOrDuplicatedIdsFallBackSafely() {
        val restored = normalizeGatewayOrder(listOf("logs", "logs", "unknown", "overview"))
        assertEquals(listOf("logs", "overview", "performance", "channels", "usage"), restored)
        assertEquals(defaults, normalizeGatewayOrder(listOf("garbage", "")))
    }

    @Test
    fun moveKeepsEveryItemExactlyOnce() {
        val moved = moveGatewayTab(defaults, 0, 2)
        assertEquals(listOf("performance", "logs", "overview", "channels", "usage"), moved)
        assertEquals(defaults.toSet(), moved.toSet())
        assertEquals(defaults.size, moved.size)
        assertEquals(defaults, moveGatewayTab(defaults, -1, 2))
        assertEquals(defaults, moveGatewayTab(defaults, 0, 99))
    }

    @Test
    fun normalizedOrdersAlwaysContainAllTabs() {
        val cases = listOf(
            null,
            emptyList(),
            listOf("usage"),
            listOf("bogus", "logs", "logs"),
            listOf("channels", "performance", "overview", "usage", "logs")
        )
        cases.forEach { saved ->
            val order = normalizeGatewayOrder(saved)
            assertEquals(defaults.size, order.size)
            assertTrue(order.containsAll(defaults))
        }
    }
}
