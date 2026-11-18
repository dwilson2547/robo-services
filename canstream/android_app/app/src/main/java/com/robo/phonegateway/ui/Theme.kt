package com.robo.phonegateway.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The DBC companion app's dark palette, so the two apps read as one family.
private val colors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF003549),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFF8B949E),
    surfaceContainer = Color(0xFF161B22),
    error = Color(0xFFFF7B72),
    tertiary = Color(0xFF3FB950),
)

@Composable
fun GatewayTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

/** Status colours: good / attention / bad / not running. */
object Status {
    val Good = Color(0xFF3FB950)
    val Warn = Color(0xFFD29922)
    val Bad = Color(0xFFFF7B72)
    val Idle = Color(0xFF8B949E)
}
