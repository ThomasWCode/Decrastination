package com.thomaswcode.decrastination.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The app's colours: dark blue (your call, 16 Oct, in place of UI-12's pink from the logo), on paper
 * by day and on near-black by night, the app, the widget and the notifications alike, in place of
 * the wallpaper's. The launcher icon keeps the logo's pink. The error reds stay Material's, as
 * overdue's standard red.
 */
object Colours {
    val Navy = Color(0xFF1E3A64)
    val NavyPale = Color(0xFFD7E3FF)
    val NavyDeep = Color(0xFF0B2F5C)
    val Sky = Color(0xFFA8C7FF)
    val Ink = Color(0xFF1A1A1A)
    val Paper = Color(0xFFFCFCFC)
    val Night = Color(0xFF121212)
    val OffWhite = Color(0xFFEDEDED)

    /** The notifications' accent, as Android takes it: a mid blue, read on a light shade and a dark one. */
    const val BLUE_ARGB = 0xFF5B8DEF.toInt()

    // By day the navy is the one read as text and controls, its pale shade filling your messages
    // and the chat's button; the surfaces are faintly blue greys, not Material's purple ones.
    val Light = lightColorScheme(
        primary = Navy, onPrimary = Color.White, primaryContainer = NavyPale, onPrimaryContainer = Color(0xFF0B1C36),
        inversePrimary = Sky,
        secondary = Color(0xFF4A6187), onSecondary = Color.White, secondaryContainer = Color(0xFFDCE6F7), onSecondaryContainer = Color(0xFF111C2B),
        tertiary = Color(0xFF2F6A8F), onTertiary = Color.White, tertiaryContainer = Color(0xFFCDE5FF), onTertiaryContainer = Color(0xFF001D31),
        background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
        surfaceVariant = Color(0xFFE1E6EF), onSurfaceVariant = Color(0xFF43474F),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F7FB), surfaceContainer = Color(0xFFEFF2F7),
        surfaceContainerHigh = Color(0xFFE9EDF3), surfaceContainerHighest = Color(0xFFE3E8EF),
        outline = Color(0xFF73787F), outlineVariant = Color(0xFFC3C7CF),
    )

    // By night the sky blue is read on near-black (about 11:1), and the navy fills the cards, your
    // messages and the chat's button, under pale blue words.
    val Dark = darkColorScheme(
        primary = Sky, onPrimary = NavyDeep, primaryContainer = Navy, onPrimaryContainer = NavyPale,
        inversePrimary = Color(0xFF2E5A97),
        secondary = Color(0xFF9DB8E6), onSecondary = Color(0xFF0F2A4A), secondaryContainer = Color(0xFF22344F), onSecondaryContainer = NavyPale,
        tertiary = Color(0xFFA3C9F5), onTertiary = Color(0xFF0A3050), tertiaryContainer = Color(0xFF1F3B57), onTertiaryContainer = Color(0xFFD2E6FF),
        background = Night, onBackground = OffWhite, surface = Night, onSurface = OffWhite,
        surfaceVariant = Color(0xFF1F2329), onSurfaceVariant = Color(0xFFC3C8D2),
        surfaceContainerLowest = Color(0xFF0D0D0F), surfaceContainerLow = Color(0xFF17191C), surfaceContainer = Color(0xFF1B1E22),
        surfaceContainerHigh = Color(0xFF23272D), surfaceContainerHighest = Color(0xFF2D3238),
        outline = Color(0xFF8C9199), outlineVariant = Color(0xFF42474E),
    )
}
