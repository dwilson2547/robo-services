package com.robo.phonegateway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun StatusLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).background(color, CircleShape))

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun DotLabel(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Dot(color, 8)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** A big number with a small label under it. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, large: Boolean = false) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Tiles laid out `columns` to a row. */
@Composable
fun TileGrid(tiles: List<Pair<String, String>>, columns: Int = 3, large: Boolean = false) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f), large) }
                repeat(columns - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
