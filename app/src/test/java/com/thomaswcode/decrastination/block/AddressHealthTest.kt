package com.thomaswcode.decrastination.block

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddressHealthTest {
    private val chrome = "com.android.chrome"

    @Test
    fun `a browser whose address is never read in ten visits running is unreadable`() {
        val health = AddressHealth()
        // The tenth visit is counted once the eleventh begins.
        repeat(AddressHealth.LIMIT) { health.visit(chrome) }
        assertTrue(health.unreadable.isEmpty())
        health.visit(chrome)
        assertEquals(setOf(chrome), health.unreadable)
    }

    @Test
    fun `one read starts the count again, and clears it`() {
        val health = AddressHealth()
        repeat(AddressHealth.LIMIT + 1) { health.visit(chrome) }
        health.read(chrome)
        assertTrue(health.unreadable.isEmpty())
        // A visit with its address read doesn't count, nor do a few without (a video full screen).
        repeat(5) {
            health.visit(chrome)
            health.read(chrome)
        }
        repeat(AddressHealth.LIMIT - 1) { health.visit(chrome) }
        health.visit(chrome)
        assertTrue(health.unreadable.isEmpty())
    }

    @Test
    fun `browsers are counted apart`() {
        val health = AddressHealth()
        repeat(AddressHealth.LIMIT + 1) {
            health.visit(chrome)
            health.visit("com.brave.browser")
            health.read("com.brave.browser")
        }
        assertEquals(setOf(chrome), health.unreadable)
    }
}
