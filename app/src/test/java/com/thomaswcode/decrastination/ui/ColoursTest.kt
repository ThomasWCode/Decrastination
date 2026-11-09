package com.thomaswcode.decrastination.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 7f, "on surface")
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5f, "on surface variant")
        }
    }

    @Test
    fun `by night the cards and chips are light, as the website's, not a dim colour that reads murky`() {
        val dark = Colours.Dark
        for (container in listOf(dark.primaryContainer, dark.secondaryContainer, dark.tertiaryContainer)) {
            assertTrue(container.luminance() >= 0.3f, "$container is too dark")
        }
    }

    @Test
    fun `what's drawn on a lilac card in the lilac or the red reads there, and is the scheme's own by day`() {
        for (scheme in listOf(Colours.Light, Colours.Dark)) {
            assertTrue(Colours.contrast(Colours.accentOn(scheme), scheme.primaryContainer) >= 4.5f, "accent on card")
            assertTrue(Colours.contrast(Colours.errorOn(scheme), scheme.primaryContainer) >= 4.5f, "red on card")
        }
        assertEquals(Colours.Light.primary, Colours.accentOn(Colours.Light))
        assertEquals(Colours.Light.error, Colours.errorOn(Colours.Light))
    }

    @Test
    fun `on a lilac card Material's buttons, labels and marks read by night, and by day the scheme is its own`() {
        for (scheme in listOf(Colours.Light, Colours.Dark)) {
            for (card in listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer)) {
                val on = Colours.onCard(scheme, card)
                assertTrue(Colours.contrast(on.primary, card) >= 4.5f, "an outlined or text button's words on $card")
                assertTrue(Colours.contrast(on.onPrimary, on.primary) >= 4.5f, "a filled button's words on $card")
                assertTrue(Colours.contrast(on.error, card) >= 4.5f, "the red on $card")
                assertTrue(Colours.contrast(on.onSurfaceVariant, card) >= 4.5f, "the quieter words on $card")
                assertTrue(Colours.contrast(on.outline, card) >= 3f, "an outlined button's rim on $card")
            }
        }
        for (card in listOf(Colours.Light.primaryContainer, Colours.Light.secondaryContainer)) assertEquals(Colours.Light, Colours.onCard(Colours.Light, card))
        // On the night's greys the lilac reads already.
        assertEquals(Colours.Dark, Colours.onCard(Colours.Dark, Colours.Dark.surfaceContainer))
    }

    @Test
    fun `the widget's frame is dark by night, and its heading, its words and its card read on it`() {
        for ((scheme, frame) in listOf(Colours.Light to Colours.WidgetFrameDay, Colours.Dark to Colours.WidgetFrameNight)) {
            assertTrue(Colours.contrast(scheme.primary, frame) >= 4.5f, "the day's count on the frame")
            assertTrue(Colours.contrast(scheme.onSurface, frame) >= 7f, "Felix's line on the frame")
        }
        // By night the lilac card stands out from the frame round it, rather than sitting on a paler lilac.
        assertTrue(Colours.WidgetFrameNight.luminance() < 0.05f, "the night frame is dark")
        assertTrue(Colours.contrast(Colours.Dark.primaryContainer, Colours.WidgetFrameNight) >= 3f, "the card on the frame")
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

    @Test
    fun `on the Day a band's name and a free event's words read on the school band, by day and by night`() {
        for (scheme in listOf(Colours.Light, Colours.Dark)) {
            val band = Colours.schoolBand(scheme)
            assertTrue(Colours.contrast(scheme.onSurfaceVariant, band) >= 4.5f, "a band's name on the school band")
            assertTrue(Colours.contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5f, "a band's name on your time")
            assertTrue(Colours.contrast(scheme.onSurface, band) >= 4.5f, "a free event's words on the school band")
        }
        assertEquals(Colours.Light.secondaryContainer, Colours.schoolBand(Colours.Light))
        // By night the band is the graphite, as the website's, not a card's lilac.
        assertTrue(Colours.schoolBand(Colours.Dark).luminance() < 0.05f, "the night's band is dark")
    }

    @Test
    fun `on the school band the blocks stand out, by their fill or their rim`() {
        for (scheme in listOf(Colours.Light, Colours.Dark)) {
            val band = Colours.schoolBand(scheme)
            // An event with no colour of its own, a slot kept and a busy time are rimmed in the outline: by day the event's
            // lilac is the band's, and by night the slot's grey is.
            assertTrue(Colours.contrast(scheme.outline, band) >= 3f, "the outline on the school band")
            assertTrue(Colours.contrast(scheme.outline, scheme.surfaceVariant) >= 3f, "the outline on a slot kept")
        }
        // By night the cards' lilacs stand out on the graphite band by their fill alone.
        val night = Colours.schoolBand(Colours.Dark)
        for (fill in listOf(Colours.Dark.primaryContainer, Colours.Dark.primary, Colours.Dark.secondaryContainer, Colours.outsideStrip(Colours.Dark))) {
            assertTrue(Colours.contrast(fill, night) >= 3f, "$fill on the night's band")
        }
    }

    @Test
    fun `a break outside's words read on its strip over each of the Day's backgrounds, by day and by night`() {
        for (scheme in listOf(Colours.Light, Colours.Dark)) {
            val strip = Colours.outsideStrip(scheme)
            for (under in listOf(scheme.surface, scheme.surfaceContainer, Colours.schoolBand(scheme))) {
                assertTrue(Colours.contrast(scheme.onTertiaryContainer, strip.compositeOver(under)) >= 4.5f, "Outside on $under")
            }
        }
        assertEquals(0.6f, Colours.outsideStrip(Colours.Light).alpha, 0.01f)
    }
}
