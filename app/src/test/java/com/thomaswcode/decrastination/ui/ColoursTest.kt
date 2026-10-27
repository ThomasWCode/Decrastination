package com.thomaswcode.decrastination.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** The words on an event's own colour (4.6.0). */
class ColoursTest {
    @Test
    fun `an event's words are white on a dark colour and black on a light one`() {
        assertEquals(Color.White, Colours.onEvent(0xFF1A237E.toInt()))
        assertEquals(Color.Black, Colours.onEvent(0xFFFFEB3B.toInt()))
    }
}
