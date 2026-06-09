package com.robo.phonecompanion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF003549),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFFF7B72),
    tertiary = Color(0xFF3FB950),  // verified green
)

@Composable
fun PhoneCompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// Semantic colours used across screens
val ColorVerified = Color(0xFF3FB950)
val ColorSuspect = Color(0xFFD29922)
val ColorUnknown = Color(0xFF8B949E)
val ColorActive = Color(0xFF4FC3F7)
