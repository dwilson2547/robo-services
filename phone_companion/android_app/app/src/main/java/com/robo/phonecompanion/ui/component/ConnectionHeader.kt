package com.robo.phonecompanion.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.ConnectionState

@Composable
fun ConnectionHeader(state: ConnectionState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        is ConnectionState.Disconnected -> "Disconnected" to ColorUnknown
        is ConnectionState.Scanning -> "Scanning..." to ColorActive
        is ConnectionState.Connecting -> "Connecting to ${state.deviceName}" to ColorActive
        is ConnectionState.Connected -> {
            val fps = "%.0f".format(state.frameRateHz)
            "${state.deviceName}  ${state.rssi} dBm  ${fps} fps" to ColorActive
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}
