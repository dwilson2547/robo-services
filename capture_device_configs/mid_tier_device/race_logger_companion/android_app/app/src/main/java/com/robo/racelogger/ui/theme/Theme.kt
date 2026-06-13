package com.robo.racelogger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colorScheme = darkColorScheme(
    primary    = Color(0xFF4FC3F7),
    onPrimary  = Color(0xFF003549),
    surface    = Color(0xFF0D1117),
    onSurface  = Color(0xFFE6EDF3),
    surfaceVariant    = Color(0xFF161B22),
    onSurfaceVariant  = Color(0xFF8B949E),
    error      = Color(0xFFFF7B72),
)

@Composable
fun RaceLoggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// Status LED colours
val ColorBoot    = Color(0xFFFF7B72)   // red
val ColorWaiting = Color(0xFFD29922)   // yellow/amber
val ColorReady   = Color(0xFF3FB950)   // green
val ColorUnknown = Color(0xFF484F58)   // grey
