package com.running2.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand orange taken from the launcher icon. */
val BrandOrange = Color(0xFFFF6F00)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE85D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF341000),
    secondary = Color(0xFF146AD2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF001B3E),
    background = Color(0xFFFBF8F6),
    onBackground = Color(0xFF1F1B18),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1B18),
    surfaceVariant = Color(0xFFF3ECE7),
    onSurfaceVariant = Color(0xFF5B514B),
    outline = Color(0xFF8D817A),
    outlineVariant = Color(0xFFE0D6CF),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68D),
    onPrimary = Color(0xFF552000),
    primaryContainer = Color(0xFF7A3000),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFA9C7FF),
    onSecondary = Color(0xFF003063),
    secondaryContainer = Color(0xFF00468C),
    onSecondaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF171311),
    onBackground = Color(0xFFEDE0DA),
    surface = Color(0xFF1F1A17),
    onSurface = Color(0xFFEDE0DA),
    surfaceVariant = Color(0xFF2E2622),
    onSurfaceVariant = Color(0xFFD7C3B9),
    outline = Color(0xFFA08D84),
    outlineVariant = Color(0xFF52443C),
    error = Color(0xFFFFB4AB)
)

@Composable
fun RunLiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
