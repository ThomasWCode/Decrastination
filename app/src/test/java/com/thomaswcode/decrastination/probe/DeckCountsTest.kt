package com.thomaswcode.decrastination.probe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeckCountsTest {

    @Test
    fun `reads learn, review and new in that order`() {
        assertEquals(DeckCounts(learn = 2, review = 15, new = 20), DeckCounts.parse("[2, 15, 20]"))
        assertEquals(DeckCounts(0, 0, 20), DeckCounts.parse("[0,0,20]"))
    }

    @Test
    fun `anything but three whole numbers is refused`() {
        assertNull(DeckCounts.parse(null))
        assertNull(DeckCounts.parse(""))
        assertNull(DeckCounts.parse("[1, 2]"))
        assertNull(DeckCounts.parse("[1, 2, 3, 4]"))
        assertNull(DeckCounts.parse("[1, x, 3]"))
        assertNull(DeckCounts.parse("1, 2, 3"))
    }
}
