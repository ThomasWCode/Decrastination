package com.thomaswcode.decrastination.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The words on an event's own colour (4.6.0), and the lilac's (D-150). */
class ColoursTest {
    @Test
    fun `an event's words are white on a dark colour and black on a light one`() {
        assertEquals(Color.White, Colours.onEvent(0xFF1A237E.toInt()))
        assertEquals(Color.Black, Colours.onEvent(0xFFFFEB3B.toInt()))
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = max(a.luminance(), b.luminance()) to min(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun `the lilac and the words on it read, by day and by night`() {
        for (scheme in listOf(Colours.Light, Colours.Dark)) {
            assertTrue(contrast(scheme.primary, scheme.surface) >= 4.5f, "primary on surface")
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f, "on primary")
            assertTrue(contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5f, "on primary container")
            // The lilac's own words are written on its cards too (the widget's title, the chat's labels).
            assertTrue(contrast(scheme.primary, scheme.primaryContainer) >= 4.5f, "primary on primary container")
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 7f, "on surface")
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5f, "on surface variant")
        }
    }

    @Test
    fun `by night the surfaces are greys that never lean warm, so none reads as brown`() {
        val dark = Colours.Dark
        for (surface in listOf(dark.surface, dark.surfaceVariant, dark.surfaceContainerLowest, dark.surfaceContainerLow, dark.surfaceContainer, dark.surfaceContainerHigh, dark.surfaceContainerHighest)) {
            val channels = listOf(surface.red, surface.green, surface.blue)
            // A cool lean of a few steps in 255 is allowed (the website's #26262B leans 5 to blue); red over blue never.
            assertTrue(channels.max() - channels.min() <= 6 / 255f, "$surface: more than a lean")
            assertTrue(surface.red <= surface.blue, "$surface leans warm")
        }
    }
}
