package com.thomaswcode.decrastination.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The logo's colours (UI-12): its pink on paper by day and on near-black by night, the app, the
 * widget and the notifications alike, in place of the wallpaper's. The error reds stay Material's,
 * as overdue's standard red.
 */
object Colours {
    val Pink = Color(0xFFFFAAAA)
    val PinkPale = Color(0xFFFFD6D6)
    val PinkDeep = Color(0xFF5A2E2E)
    val Ink = Color(0xFF1A1A1A)
    val Paper = Color(0xFFFCFCFC)
    val Night = Color(0xFF121212)
    val NightRaised = Color(0xFF1E1E1E)
    val Rose = Color(0xFFB86B6B)
    val OffWhite = Color(0xFFEDEDED)

    /** The notifications' accent, as Android takes it. */
    const val PINK_ARGB = 0xFFFFAAAA.toInt()

    val Light = lightColorScheme(
        primary = Pink, onPrimary = Ink, primaryContainer = PinkPale, onPrimaryContainer = Ink,
        secondary = Rose, onSecondary = Paper, secondaryContainer = PinkPale, onSecondaryContainer = Ink,
        surface = Paper, onSurface = Ink, surfaceVariant = Color(0xFFF2EAEA), onSurfaceVariant = Color(0xFF4A4040),
        background = Paper, onBackground = Ink, outline = Color(0xFF8A7A7A),
    )

    val Dark = darkColorScheme(
        primary = Pink, onPrimary = Ink, primaryContainer = PinkDeep, onPrimaryContainer = PinkPale,
        secondary = Rose, onSecondary = Ink, secondaryContainer = PinkDeep, onSecondaryContainer = PinkPale,
        surface = Night, onSurface = OffWhite, surfaceVariant = NightRaised, onSurfaceVariant = Color(0xFFCFC5C5),
        background = Night, onBackground = OffWhite, outline = Color(0xFF6A5E5E),
    )
}
