package com.thomaswcode.decrastination.sources.anki

import kotlin.test.Test
import kotlin.test.assertEquals

/** A deck's unseen cards asked again only when its counts or options change (AR-08). */
class UnseenCacheTest {
    @Test
    fun `a deck's unseen cards are asked once while its counts and options stay as they were`() {
        val cache = UnseenCache()
        var asked = 0
        val deck = Deck(1, "Textbook 1::1.2", learn = 0, review = 3, new = 20, newPerDay = 20)
        assertEquals(72, cache.get(deck, 0) { asked++; 72 })
        assertEquals(72, cache.get(deck, 60_000) { asked++; 99 })
        assertEquals(1, asked)
        // Studied: its counts fall, and it's asked again.
        assertEquals(70, cache.get(deck.copy(new = 18), 120_000) { asked++; 70 })
        // Its new cards a day changed in AnkiDroid: asked again too.
        assertEquals(70, cache.get(deck.copy(new = 18, newPerDay = 10), 180_000) { asked++; 70 })
        assertEquals(3, asked)
        // Cards added while today's new ones are at their limit change no count: asked again within the hour.
        assertEquals(80, cache.get(deck.copy(new = 18, newPerDay = 10), 180_000 + UnseenCache.MAX_AGE_MS) { asked++; 80 })
        assertEquals(4, asked)
    }
}
