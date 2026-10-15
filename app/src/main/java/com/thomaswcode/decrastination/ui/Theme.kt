package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** The logo's colours (UI-12), light or dark as the phone is: the same on every phone, not the wallpaper's. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Colours.Dark else Colours.Light, content = content)
}
