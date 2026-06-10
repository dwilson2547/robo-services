package com.robo.phonecompanion.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robo.phonecompanion.data.model.SessionMeta
import com.robo.phonecompanion.vm.SettingsViewModel
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import androidx.compose.material3.Icon

@Composable
fun VehicleDetailScreen(
    vm: SettingsViewModel,
    vehicleId: String,
    onEditVehicle: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vehicles by vm.vehicles.collectAsState()
    val sessions by vm.vehicleSessions.collectAsState()
    val vehicle = vehicles.find { it.id == vehicleId }

    LaunchedEffect(vehicleId) {
        vm.loadSessionsForVehicle(vehicleId)
    }

    if (vehicle == null) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
            Text("Vehicle not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(vehicle.nickname, style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append("${vehicle.year} ${vehicle.make} ${vehicle.model}")
                        if (vehicle.engine.isNotBlank()) append(" · ${vehicle.engine}")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (vehicle.notes.isNotBlank()) {
                    Text(
                        vehicle.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedButton(onClick = onEditVehicle, modifier = Modifier.fillMaxWidth()) {
            Text("Edit profile")
        }

        HorizontalDivider()

        Text("Recordings", style = MaterialTheme.typography.titleMedium)

        if (sessions.isEmpty()) {
            Text(
                "No recordings for this vehicle yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(session = session, onClick = { onOpenSession(session.id) })
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionMeta, onClick: () -> Unit) {
    val dateLabel = session.startTime.take(16).replace('T', ' ')
    val durationLabel = formatDuration(session)
    ListItem(
        headlineContent = { Text(dateLabel) },
        supportingContent = {
            Text(
                buildString {
                    append("${session.frameCount} frames")
                    if (durationLabel != null) append("  ·  $durationLabel")
                    if (session.dbcId != "none") append("  ·  ${session.dbcId}")
                    if (session.notes.isNotBlank()) append("\n${session.notes}")
                },
            )
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun formatDuration(session: SessionMeta): String? {
    val end = session.endTime ?: return null
    return runCatching {
        val start = LocalDateTime.parse(session.startTime)
        val endDt = LocalDateTime.parse(end)
        val secs = ChronoUnit.SECONDS.between(start, endDt)
        if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }.getOrNull()
}
