package com.robo.phonegateway.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robo.phonegateway.GatewayStatus
import com.robo.phonegateway.session.LastSession
import com.robo.phonegateway.telemetry.LapAnchor
import androidx.compose.ui.platform.LocalContext

/** The current drive: vehicle, start/stop, mark start/finish, and the last drive. */
@Composable
fun DriveScreen(app: App, now: Long, onOpenSession: (Long) -> Unit) {
    val signedIn by app.auth.signedIn.collectAsStateWithLifecycle()
    val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
    val fixFresh = status.lastFixMs > 0 && now - status.lastFixMs <= LapAnchor.MAX_FIX_AGE_MS
    val context = LocalContext.current

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!signedIn) {
            Text("Sign in to record and upload drives.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = app::startSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            return@Column
        }
        VehiclePicker(app, status)
        if (status.running) {
            Button(onClick = app::stopCapture, modifier = Modifier.fillMaxWidth()) { Text("Stop capture") }
            Button(onClick = app::markStart, enabled = fixFresh, modifier = Modifier.fillMaxWidth()) {
                Text("Mark start/finish here")
            }
            Text(
                when {
                    !fixFresh -> "waiting for a GPS fix"
                    status.lastAnchor != null -> "Last anchor: ${status.lastAnchor}"
                    else -> "Lap timing starts once a start/finish is marked"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            StatusLine("Frames", "${status.framesLogged} logged, ${status.framesAcked} acked, ${status.framesNotSent} not sent")
        } else {
            Button(onClick = app::startCapture, modifier = Modifier.fillMaxWidth()) { Text("Start capture") }
        }
        status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!status.running) {
            val sessionId = status.sessionId.takeIf { it != 0L } ?: LastSession.get(context)
            if (sessionId != 0L) {
                HorizontalDivider()
                Text(
                    "Open in History ›",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onOpenSession(sessionId) },
                )
                SummaryView(app, sessionId, "Last drive")
            }
        }
    }
}

@Composable
private fun VehiclePicker(app: App, status: GatewayStatus.Snapshot) {
    val list by app.vehicles.vehicles.collectAsStateWithLifecycle()
    val selectedId by app.vehicles.selectedId.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val selected = list.firstOrNull { it.id == selectedId }

    LaunchedEffect(Unit) { app.refreshVehicles() }
    // the dongle usually lives in one car: preselect the car it was last used in
    LaunchedEffect(status.dongleConnected) {
        if (status.dongleConnected && !status.running) {
            val car = app.dongleSettings.rememberedAddress?.let { app.vehicles.vehicleForDongle(it) }
            if (car != null && car.id != app.vehicles.selectedId.value) app.selectVehicle(car)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Vehicle", style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { menuOpen = true }, enabled = !status.running) {
                Text(selected?.label() ?: "choose…")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                list.sortedBy { it.name }.forEach { v ->
                    DropdownMenuItem(text = { Text(v.label()) }, onClick = { menuOpen = false; app.selectVehicle(v) })
                }
                DropdownMenuItem(text = { Text("Add vehicle…") }, onClick = { menuOpen = false; adding = true })
            }
        }
    }
    if (adding) VehicleDialog(app, null) { adding = false }
}
