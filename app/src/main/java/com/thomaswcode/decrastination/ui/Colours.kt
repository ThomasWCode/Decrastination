package com.thomaswcode.decrastination.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The app's colours: lilac (D-150), on a faintly lilac paper by day and on neutral graphite by night,
 * the app, the widget and the notifications alike, in place of the wallpaper's, and the website's the
 * same (`web/src/styles.css`). By night the surfaces carry no hue, as a dark shade of a warm one reads
 * as brown: the lilac is only light on them. The launcher icon keeps the logo's pink. The error reds
 * stay Material's, as overdue's standard red.
 */
object Colours {
    val Lilac = Color(0xFF5B3FA0)
    val LilacPale = Color(0xFFE3DAFF)
    val LilacDeep = Color(0xFF1B0F40)
    val LilacLight = Color(0xFFC9B8FF)
    val Ink = Color(0xFF1E1B26)
    val Paper = Color(0xFFFBFAFF)
    val Night = Color(0xFF141416)
    val OffWhite = Color(0xFFEDEDED)

    /**
     * The words on an event's own colour ([argb], the calendar's, 4.6.0): black or white, whichever has the greater contrast on
     * it (their contrasts are equal at a luminance of about 0.179).
     */
    fun onEvent(argb: Int): Color = if (Color(argb).luminance() > 0.179f) Color.Black else Color.White

    /** How far apart two colours read, as WCAG has it: 1 the same, 21 black on white. */
    fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** The lilac for words and marks on a lilac card ([container], [ColorScheme.primaryContainer] unless given): the lilac itself where it reads there (by day), the card's own words' colour where it doesn't (by night, on the light lilac). */
    fun accentOn(scheme: ColorScheme, container: Color = scheme.primaryContainer): Color = if (contrast(scheme.primary, container) >= 4.5f) scheme.primary else scheme.onPrimaryContainer

    /** The red for words and marks on a lilac card ([container], [ColorScheme.primaryContainer] unless given): the scheme's own where it reads there (by day), a deep red where it doesn't (by night, as its light red is the lilac's lightness). */
    fun errorOn(scheme: ColorScheme, container: Color = scheme.primaryContainer): Color = if (contrast(scheme.error, container) >= 4.5f) scheme.error else DeepRed

    private val DeepRed = Color(0xFF5C0F0B)

    /** The grey for a card's quieter words and its outlined buttons' rims by night, on the light lilacs (over 5:1 on each). */
    private val CardGrey = Color(0xFF2E2E33)

    /**
     * The scheme for what's drawn on a card of [container]: [scheme] itself where its lilac reads there (by day, and on the
     * night's greys). By night, on a light lilac card, Material's buttons, labels and marks would draw in the light lilac and
     * the light red, the card's own lightness: there the lilac is the card's near-black, a filled button near-black under light
     * lilac words, the red [errorOn]'s, and the greys a dark one, so an outlined or a text button's words, a disabled one's, a
     * switch's and a field's read on it.
     */
    fun onCard(scheme: ColorScheme, container: Color): ColorScheme {
        if (contrast(scheme.primary, container) >= 4.5f) return scheme
        val ink = scheme.onPrimaryContainer
        return scheme.copy(
            primary = ink, onPrimary = scheme.primary,
            secondary = ink, onSecondary = scheme.primary,
            error = errorOn(scheme, container), onError = Color.White,
            onSurface = ink, onSurfaceVariant = CardGrey, outline = CardGrey,
        )
    }

    /**
     * The widget's frame round its card and list, by day and by night. By day Glance's own (a tone above the secondary
     * container); by night the graphite, as Glance's, a tone above the light lilac secondary container, is paler than the
     * card it holds and hides the lilac words on it.
     */
    val WidgetFrameDay = Color(0xFFF4EDFF)
    val WidgetFrameNight = Night

    /** The notifications' accent, as Android takes it: a mid lilac, read on a light shade and a dark one. */
    const val LILAC_ARGB = 0xFF8C6FE0.toInt()

    // By day the lilac is the one read as text and controls (about 7:1 on the paper), its pale shade
    // filling your messages and the chat's button; the surfaces are faintly lilac greys.
    val Light = lightColorScheme(
        primary = Lilac, onPrimary = Color.White, primaryContainer = LilacPale, onPrimaryContainer = LilacDeep,
        inversePrimary = LilacLight,
        secondary = Color(0xFF5D5577), onSecondary = Color.White, secondaryContainer = Color(0xFFE6DEF8), onSecondaryContainer = Color(0xFF1B1530),
        tertiary = Color(0xFF7A4B86), onTertiary = Color.White, tertiaryContainer = Color(0xFFF5D9FF), onTertiaryContainer = Color(0xFF2F0B3A),
        background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
        surfaceVariant = Color(0xFFE7E2F2), onSurfaceVariant = Color(0xFF544F60),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F3FD), surfaceContainer = Color(0xFFF1EDFB),
        surfaceContainerHigh = Color(0xFFEBE6F8), surfaceContainerHighest = Color(0xFFE5E0F4),
        outline = Color(0xFF7A7487), outlineVariant = Color(0xFFDCD6EA),
    )

    // By night, as the website's: the light lilac is read on graphite (about 11:1), and the cards, the Day's
    // pieces, your messages and the chips are light lilacs under near-black words, the piece now the lightest.
    // Neither a dim lilac grey (murky) nor a deep violet (neon): the owner, 5 Nov. What's drawn on a card in the
    // lilac or the red takes [accentOn] and [errorOn], which read there.
    val Dark = darkColorScheme(
        primary = Color(0xFFCFC0FF), onPrimary = Color(0xFF1A1A1A), primaryContainer = Color(0xFFA898E3), onPrimaryContainer = Color(0xFF1A1A1A),
        inversePrimary = Lilac,
        secondary = Color(0xFFD4C8FF), onSecondary = Color(0xFF1A1A1A), secondaryContainer = Color(0xFFC2B6EE), onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFFF2B8FF), onTertiary = Color(0xFF1A1A1A), tertiaryContainer = Color(0xFFD9B8F0), onTertiaryContainer = Color(0xFF1A1A1A),
        background = Night, onBackground = OffWhite, surface = Night, onSurface = OffWhite,
        surfaceVariant = Color(0xFF26262B), onSurfaceVariant = Color(0xFFB9B9C0),
        surfaceContainerLowest = Color(0xFF0E0E10), surfaceContainerLow = Color(0xFF1B1B1E), surfaceContainer = Color(0xFF1F1F23),
        surfaceContainerHigh = Color(0xFF26262B), surfaceContainerHighest = Color(0xFF2E2E33),
        outline = Color(0xFF8E8E96), outlineVariant = Color(0xFF3A3A40),
    )
}
