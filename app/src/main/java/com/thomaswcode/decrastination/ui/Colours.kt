package com.thomaswcode.decrastination.ui

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

    // By night the light lilac is read on graphite (about 10:1), and on the cards, your messages and the
    // chat's button, a dim lilac grey of their own so the lilac reads on them too; the surfaces are neutral greys.
    val Dark = darkColorScheme(
        primary = LilacLight, onPrimary = Color(0xFF2A1A5E), primaryContainer = Color(0xFF3A3150), onPrimaryContainer = Color(0xFFEDE6FF),
        inversePrimary = Lilac,
        secondary = Color(0xFFCBC2DB), onSecondary = Color(0xFF332D41), secondaryContainer = Color(0xFF34313B), onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFE5B8F0), onTertiary = Color(0xFF45204F), tertiaryContainer = Color(0xFF3A3540), onTertiaryContainer = Color(0xFFF5D9FF),
        background = Night, onBackground = OffWhite, surface = Night, onSurface = OffWhite,
        surfaceVariant = Color(0xFF26262B), onSurfaceVariant = Color(0xFFB9B9C0),
        surfaceContainerLowest = Color(0xFF0E0E10), surfaceContainerLow = Color(0xFF1B1B1E), surfaceContainer = Color(0xFF1F1F23),
        surfaceContainerHigh = Color(0xFF26262B), surfaceContainerHighest = Color(0xFF2E2E33),
        outline = Color(0xFF8E8E96), outlineVariant = Color(0xFF3A3A40),
    )
}
