package com.robo.racelogger.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.racelogger.ui.theme.ColorBoot
import com.robo.racelogger.ui.theme.ColorReady
import com.robo.racelogger.ui.theme.ColorUnknown
import com.robo.racelogger.ui.theme.ColorWaiting
import com.robo.racelogger.vm.DeviceStatus
import com.robo.racelogger.vm.RaceLoggerViewModel

@Composable
fun StatusScreen(vm: RaceLoggerViewModel, onConfigClick: () -> Unit, onWifiClick: () -> Unit) {
    val deviceName   by vm.deviceName.collectAsState()
    val deviceStatus by vm.deviceStatus.collectAsState()
    val stagingResult by vm.stagingResult.collectAsState()

    val gpsLocked by vm.gpsLocked.collectAsState()
    val ppsLocked by vm.ppsLocked.collectAsState()
    val canFlow   by vm.canFlow.collectAsState()
    val imuOk     by vm.imuOk.collectAsState()

    val ledColor by animateColorAsState(
        targetValue = when (deviceStatus) {
            DeviceStatus.BOOT    -> ColorBoot
            DeviceStatus.WAITING -> ColorWaiting
            DeviceStatus.READY   -> ColorReady
            DeviceStatus.UNKNOWN -> ColorUnknown
        },
        animationSpec = tween(durationMillis = 400),
        label = "ledColor",
    )

    val statusLabel = when (deviceStatus) {
        DeviceStatus.BOOT    -> "BOOT"
        DeviceStatus.WAITING -> "WAITING"
        DeviceStatus.READY   -> "READY"
        DeviceStatus.UNKNOWN -> "—"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            deviceName ?: "race-logger",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(ledColor),
        )

        Text(
            statusLabel,
            style = MaterialTheme.typography.headlineSmall,
            color = ledColor,
        )

        // Component breakdown — only shown once we have data (byte 1 received)
        if (gpsLocked != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ComponentRow("GPS",  gpsLocked)
                    ComponentRow("PPS",  ppsLocked)
                    ComponentRow("CAN",  canFlow)
                    ComponentRow("IMU",  imuOk)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { vm.stagingPush() },
            modifier = Modifier.size(width = 220.dp, height = 60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("Mark Staging", fontSize = 18.sp)
        }

        stagingResult?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onConfigClick) { Text("Configure MQTT →") }
        TextButton(onClick = onWifiClick)   { Text("Configure WiFi →") }
        TextButton(onClick = { vm.disconnect() }) {
            Text("Disconnect", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComponentRow(label: String, state: Boolean?) {
    val ok = state ?: false
    val dotColor by animateColorAsState(
        targetValue = when {
            state == null -> ColorUnknown
            ok            -> ColorReady
            else          -> ColorBoot
        },
        animationSpec = tween(300),
        label = "$label dot",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (state == null) "—" else if (ok) "OK" else "NOT READY",
                style = MaterialTheme.typography.bodyMedium,
                color = dotColor,
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}
