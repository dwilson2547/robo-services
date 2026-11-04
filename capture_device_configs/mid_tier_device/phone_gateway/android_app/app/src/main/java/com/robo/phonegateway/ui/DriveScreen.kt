package com.robo.phonegateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robo.phonegateway.GatewayStatus
import com.robo.phonegateway.registry.DriveSummary
import com.robo.phonegateway.registry.Units
import com.robo.phonegateway.session.CaptureMode
import com.robo.phonegateway.session.LastSession
import com.robo.phonegateway.telemetry.LapAnchor

/** The current drive: set up and start it, watch it while recording, and the last one after. */
@Composable
fun DriveScreen(app: App, now: Long, onOpenSession: (Long) -> Unit) {
    val signedIn by app.auth.signedIn.collectAsStateWithLifecycle()
    val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            !signedIn -> {
                Text("Sign in to record and upload drives.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = app::startSignIn, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Sign in") }
            }
            status.running -> Recording(app, status, now)
            else -> Idle(app, status, onOpenSession)
        }
    }
}

@Composable
private fun Idle(app: App, status: GatewayStatus.Snapshot, onOpenSession: (Long) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(CaptureMode.get(context)) }

    VehiclePicker(app, status)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Mode", style = MaterialTheme.typography.bodyLarge)
        listOf(CaptureMode.TRIP to "Trip", CaptureMode.RACE to "Race").forEach { (value, label) ->
            FilterChip(
                selected = mode == value,
                onClick = { mode = value; CaptureMode.set(context, value) },
                label = { Text(label) },
            )
        }
    }
    if (mode == CaptureMode.RACE) {
        Text(
            "Track: none yet (picking one comes with the Tracks tab). Mark start/finish while recording to time laps.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = app::startCapture, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Text("Start ${if (mode == CaptureMode.RACE) "race" else "trip"}", style = MaterialTheme.typography.titleMedium)
    }
    status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val sessionId = status.sessionId.takeIf { it != 0L } ?: LastSession.get(context)
    if (sessionId != 0L) LastDrive(app, sessionId) { onOpenSession(sessionId) }
}

@Composable
private fun LastDrive(app: App, sessionId: Long, onOpen: () -> Unit) {
    val state = rememberSummary(app, sessionId)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Last drive", style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onClick = state.refresh) { Text("Refresh") }
            TextButton(onClick = onOpen) { Text("Open ›") }
        }
    }
    val s: DriveSummary? = state.summary
    if (s == null) Text(state.note ?: "loading…", style = MaterialTheme.typography.bodySmall)
    else TileGrid(s.tiles(Units.state(LocalContext.current).collectAsStateWithLifecycle().value))
}

@Composable
private fun Recording(app: App, status: GatewayStatus.Snapshot, now: Long) {
    val race = status.mode == CaptureMode.RACE
    val fixFresh = status.lastFixMs > 0 && now - status.lastFixMs <= LapAnchor.MAX_FIX_AGE_MS
    val elapsedS = ((now - status.sessionId) / 1000).coerceAtLeast(0).toDouble()
    val units by Units.state(LocalContext.current).collectAsStateWithLifecycle()

    Text(
        (if (race) "Recording race" else "Recording trip") +
            (app.vehicles.selected?.let { " · ${it.name}" } ?: ""),
        style = MaterialTheme.typography.titleMedium,
    )
    TileGrid(
        listOf(
            DriveSummary.duration(elapsedS) to "elapsed",
            units.distance(status.liveDistanceM / 1000) to "distance (GPS)",
            (status.liveSpeedMps?.let { units.speed(it * 3.6) } ?: "–") to "speed (GPS)",
        ),
        large = true,
    )
    Health(status)
    if (race) {
        OutlinedButton(onClick = app::markStart, enabled = fixFresh, modifier = Modifier.fillMaxWidth().height(56.dp)) {
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
    }
    Button(
        onClick = app::stopCapture,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
    status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** One line on whether the drive is reaching the cluster. */
@Composable
private fun Health(status: GatewayStatus.Snapshot) {
    val behind = status.framesLogged - status.framesAcked
    val (color, text) = when {
        !status.broker.startsWith("connected") && status.broker != "connecting" ->
            Status.Bad to "Not uploading (${status.broker}); frames are kept and sent later"
        behind > 5 || status.broker == "connecting" -> Status.Warn to "Uploading, $behind seconds behind"
        else -> Status.Good to "Uploading, up to date"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(color, 12)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        "${status.framesLogged} s recorded · ${status.framesAcked} uploaded" +
            if (status.framesNotSent > 0) " · ${status.framesNotSent} not sent" else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
            OutlinedButton(onClick = { menuOpen = true }) { Text(selected?.label() ?: "choose…") }
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
