package com.piru.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PiruPink = Color(0xFFEB4470)
val PiruPinkDark = Color(0xFFFF7A9E)

private val PiruColors = darkColorScheme(
    primary = PiruPink,
    onPrimary = Color.White,
    secondary = PiruPinkDark,
    background = Color(0xFF17121B),
    surface = Color(0xFF1F1A24),
    surfaceVariant = Color(0xFF2A2430),
    onBackground = Color(0xFFF2EAF0),
    onSurface = Color(0xFFF2EAF0),
    onSurfaceVariant = Color(0xFFB9AEB6),
    outline = Color(0xFF453D4A),
)

@Composable
fun PiruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PiruColors,
        typography = Typography(),
        content = content,
    )
}
